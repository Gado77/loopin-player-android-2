CREATE TABLE public.device_commands (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id UUID NOT NULL REFERENCES public.devices(id) ON DELETE CASCADE,
    screen_id UUID NOT NULL REFERENCES public.screens(id) ON DELETE CASCADE,
    owner_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    command_type TEXT NOT NULL CHECK (command_type IN ('GET_STATUS','SYNC_NOW','RELOAD_PLAYLIST')),
    payload JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(payload) = 'object' AND payload = '{}'::jsonb),
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','DELIVERED','SUCCEEDED','FAILED','EXPIRED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    delivered_at TIMESTAMPTZ,
    next_delivery_at TIMESTAMPTZ,
    delivery_attempts INTEGER NOT NULL DEFAULT 0 CHECK (delivery_attempts >= 0),
    completed_at TIMESTAMPTZ,
    result JSONB CHECK (result IS NULL OR (jsonb_typeof(result) = 'object' AND octet_length(result::text) <= 4096))
);

CREATE INDEX device_commands_delivery_idx ON public.device_commands(device_id, status, next_delivery_at, created_at);
CREATE INDEX device_commands_owner_history_idx ON public.device_commands(owner_id, screen_id, created_at DESC);
ALTER TABLE public.device_commands ENABLE ROW LEVEL SECURITY;
CREATE POLICY device_commands_select_own ON public.device_commands FOR SELECT TO authenticated USING (owner_id = auth.uid());
REVOKE ALL ON public.device_commands FROM anon, authenticated;
GRANT SELECT ON public.device_commands TO authenticated;

CREATE OR REPLACE FUNCTION public.enqueue_player_command(p_screen_id UUID, p_command_type TEXT, p_payload JSONB DEFAULT NULL)
RETURNS public.device_commands LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE v_owner UUID := auth.uid(); v_device UUID; v_result public.device_commands%ROWTYPE;
BEGIN
  IF v_owner IS NULL THEN RAISE EXCEPTION 'authentication required'; END IF;
  IF p_command_type NOT IN ('GET_STATUS','SYNC_NOW','RELOAD_PLAYLIST') THEN RAISE EXCEPTION 'unsupported command'; END IF;
  IF p_payload IS NOT NULL AND p_payload <> '{}'::jsonb THEN RAISE EXCEPTION 'payload is not accepted'; END IF;
  SELECT d.id INTO v_device FROM public.screens s JOIN public.devices d ON d.screen_id=s.id
    WHERE s.id=p_screen_id AND s.owner_id=v_owner AND s.status='ACTIVE' AND d.pairing_status='PAIRED';
  IF v_device IS NULL THEN RAISE EXCEPTION 'paired player unavailable'; END IF;
  INSERT INTO public.device_commands(device_id,screen_id,owner_id,command_type,payload,expires_at)
    VALUES(v_device,p_screen_id,v_owner,p_command_type,'{}'::jsonb,now()+interval '15 minutes') RETURNING * INTO v_result;
  RETURN v_result;
END; $$;

CREATE OR REPLACE FUNCTION public.claim_player_commands(p_device_id UUID, p_limit INTEGER DEFAULT 5)
RETURNS SETOF public.device_commands LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
  IF p_limit < 1 OR p_limit > 5 THEN RAISE EXCEPTION 'invalid claim limit'; END IF;
  UPDATE public.device_commands SET status='EXPIRED',completed_at=now(),result='{"code":"expired"}'::jsonb
    WHERE device_id=p_device_id AND status IN ('PENDING','DELIVERED') AND expires_at<=now();
  RETURN QUERY
    WITH selected AS (
      SELECT id FROM public.device_commands
      WHERE device_id=p_device_id AND status IN ('PENDING','DELIVERED') AND expires_at>now()
        AND (next_delivery_at IS NULL OR next_delivery_at<=now())
      ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT p_limit
    )
    UPDATE public.device_commands c SET status='DELIVERED',delivered_at=COALESCE(c.delivered_at,now()),
      next_delivery_at=now()+interval '60 seconds',delivery_attempts=c.delivery_attempts+1
    FROM selected WHERE c.id=selected.id RETURNING c.*;
END; $$;

CREATE OR REPLACE FUNCTION public.complete_player_command(
  p_device_id UUID, p_command_id UUID, p_status TEXT, p_result JSONB
) RETURNS public.device_commands LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE v_command public.device_commands%ROWTYPE;
BEGIN
  SELECT * INTO v_command FROM public.device_commands WHERE id=p_command_id AND device_id=p_device_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'command unavailable'; END IF;
  IF v_command.status IN ('SUCCEEDED','FAILED','EXPIRED') THEN RETURN v_command; END IF;
  IF v_command.expires_at<=now() THEN
    UPDATE public.device_commands SET status='EXPIRED',completed_at=now(),result='{"code":"expired"}'::jsonb
      WHERE id=v_command.id RETURNING * INTO v_command; RETURN v_command;
  END IF;
  IF p_status NOT IN ('SUCCEEDED','FAILED') OR jsonb_typeof(p_result)<>'object' OR octet_length(p_result::text)>4096
  THEN RAISE EXCEPTION 'invalid command result'; END IF;
  IF v_command.command_type='GET_STATUS' THEN
    IF EXISTS (SELECT 1 FROM jsonb_object_keys(p_result) k WHERE k NOT IN
      ('app_version','connection','playback_state','cache_state','sync_state','health_state','free_storage_bytes','last_sync_epoch_ms'))
      OR NOT p_result ?& ARRAY['app_version','connection','playback_state','cache_state','sync_state','health_state','free_storage_bytes','last_sync_epoch_ms']
    THEN RAISE EXCEPTION 'invalid status result'; END IF;
  ELSE
    IF EXISTS (SELECT 1 FROM jsonb_object_keys(p_result) k WHERE k<>'code') OR NOT p_result ? 'code'
       OR length(COALESCE(p_result->>'code','')) NOT BETWEEN 1 AND 64
    THEN RAISE EXCEPTION 'invalid action result'; END IF;
  END IF;
  UPDATE public.device_commands SET status=p_status,completed_at=now(),result=p_result,next_delivery_at=NULL
    WHERE id=v_command.id RETURNING * INTO v_command;
  RETURN v_command;
END; $$;

REVOKE ALL ON FUNCTION public.enqueue_player_command(UUID,TEXT,JSONB) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.enqueue_player_command(UUID,TEXT,JSONB) TO authenticated;
REVOKE ALL ON FUNCTION public.claim_player_commands(UUID,INTEGER) FROM PUBLIC,anon,authenticated;
REVOKE ALL ON FUNCTION public.complete_player_command(UUID,UUID,TEXT,JSONB) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.claim_player_commands(UUID,INTEGER) TO service_role;
GRANT EXECUTE ON FUNCTION public.complete_player_command(UUID,UUID,TEXT,JSONB) TO service_role;
