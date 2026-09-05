CREATE TABLE public.device_runtime_status (
  device_id UUID PRIMARY KEY REFERENCES public.devices(id) ON DELETE CASCADE,
  screen_id UUID NOT NULL REFERENCES public.screens(id) ON DELETE CASCADE,
  owner_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  last_seen_at TIMESTAMPTZ NOT NULL,
  session_id TEXT NOT NULL CHECK (length(session_id) BETWEEN 1 AND 64),
  app_version TEXT NOT NULL CHECK (length(app_version) BETWEEN 1 AND 100),
  uptime_ms BIGINT NOT NULL CHECK (uptime_ms >= 0),
  available_memory_bytes BIGINT NOT NULL CHECK (available_memory_bytes >= 0),
  memory_low BOOLEAN NOT NULL,
  free_storage_bytes BIGINT NOT NULL CHECK (free_storage_bytes >= 0),
  total_storage_bytes BIGINT NOT NULL CHECK (total_storage_bytes >= 0),
  playback_state TEXT NOT NULL CHECK (playback_state IN ('PLAYING','PAUSED','ERROR','OFFLINE','IDLE')),
  cache_state TEXT NOT NULL CHECK (cache_state IN ('OK','INCOMPLETE','ERROR')),
  sync_state TEXT NOT NULL CHECK (sync_state IN ('OK','SYNCING','ERROR','NEVER_SYNCED')),
  health_state TEXT NOT NULL CHECK (health_state IN ('HEALTHY','DEGRADED','ERROR')),
  last_sync_at TIMESTAMPTZ,
  last_error_code TEXT CHECK (last_error_code IS NULL OR length(last_error_code) <= 64),
  last_error_summary TEXT CHECK (last_error_summary IS NULL OR length(last_error_summary) <= 256),
  last_error_at TIMESTAMPTZ,
  active_playlist_id TEXT CHECK (active_playlist_id IS NULL OR length(active_playlist_id) <= 100),
  active_playlist_version BIGINT,
  active_manifest_etag TEXT CHECK (active_manifest_etag IS NULL OR active_manifest_etag ~ '^[a-f0-9]{64}$'),
  previous_playlist_id TEXT CHECK (previous_playlist_id IS NULL OR length(previous_playlist_id) <= 100),
  current_item_id TEXT CHECK (current_item_id IS NULL OR length(current_item_id) <= 100),
  current_content_kind TEXT CHECK (current_content_kind IS NULL OR current_content_kind IN ('MEDIA','DYNAMIC')),
  current_media_type TEXT CHECK (current_media_type IS NULL OR current_media_type IN ('VIDEO','IMAGE','WEATHER')),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE public.device_health_events (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  device_id UUID NOT NULL REFERENCES public.devices(id) ON DELETE CASCADE,
  screen_id UUID NOT NULL REFERENCES public.screens(id) ON DELETE CASCADE,
  owner_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  event_type TEXT NOT NULL CHECK (event_type IN ('PLAYER_SESSION_STARTED','HEALTH_DEGRADED','HEALTH_RECOVERED','PLAYBACK_ERROR','PLAYBACK_RECOVERED','SYNC_FAILED','SYNC_RECOVERED','CACHE_ERROR','CACHE_RECOVERED','LOW_STORAGE','STORAGE_RECOVERED','LOW_MEMORY','MEMORY_RECOVERED')),
  severity TEXT NOT NULL CHECK (severity IN ('INFO','WARNING','ERROR')),
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  metadata JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(metadata)='object' AND octet_length(metadata::text)<=1024)
);

CREATE INDEX device_runtime_owner_screen_idx ON public.device_runtime_status(owner_id,screen_id);
CREATE INDEX device_health_events_device_time_idx ON public.device_health_events(device_id,occurred_at DESC);
CREATE INDEX device_health_events_owner_screen_time_idx ON public.device_health_events(owner_id,screen_id,occurred_at DESC);
ALTER TABLE public.device_runtime_status ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.device_health_events ENABLE ROW LEVEL SECURITY;
CREATE POLICY device_runtime_select_own ON public.device_runtime_status FOR SELECT TO authenticated USING(owner_id=auth.uid());
CREATE POLICY device_health_events_select_own ON public.device_health_events FOR SELECT TO authenticated USING(owner_id=auth.uid());
REVOKE ALL ON public.device_runtime_status,public.device_health_events FROM anon,authenticated;
GRANT SELECT ON public.device_runtime_status,public.device_health_events TO authenticated;

CREATE OR REPLACE FUNCTION public.record_device_runtime_status(p_device_id UUID,p_runtime JSONB,p_session_id TEXT,p_app_version TEXT,p_last_error JSONB DEFAULT NULL)
RETURNS public.device_runtime_status LANGUAGE plpgsql SECURITY DEFINER SET search_path=public AS $$
DECLARE old public.device_runtime_status%ROWTYPE; fresh public.device_runtime_status%ROWTYPE; d public.devices%ROWTYPE;
  screen_owner UUID; now_at TIMESTAMPTZ:=now(); low_storage BOOLEAN; old_low_storage BOOLEAN;
  error_code TEXT; error_summary TEXT; error_at TIMESTAMPTZ;
BEGIN
  SELECT * INTO d FROM public.devices WHERE id=p_device_id AND pairing_status='PAIRED' AND screen_id IS NOT NULL;
  IF NOT FOUND THEN RAISE EXCEPTION 'paired device unavailable'; END IF;
  SELECT owner_id INTO screen_owner FROM public.screens WHERE id=d.screen_id AND status='ACTIVE';
  IF screen_owner IS NULL THEN RAISE EXCEPTION 'screen unavailable'; END IF;
  IF jsonb_typeof(p_runtime)<>'object' OR length(p_session_id) NOT BETWEEN 1 AND 64 OR length(p_app_version) NOT BETWEEN 1 AND 100 THEN RAISE EXCEPTION 'invalid runtime'; END IF;
  SELECT * INTO old FROM public.device_runtime_status WHERE device_id=p_device_id FOR UPDATE;
  error_code:=NULLIF(p_last_error->>'code','');error_summary:=left(NULLIF(p_last_error->>'summary',''),256);
  IF (p_last_error->>'at_epoch_ms') ~ '^\d+$' THEN error_at:=to_timestamp((p_last_error->>'at_epoch_ms')::double precision/1000); END IF;
  low_storage:=COALESCE((p_runtime->>'free_storage_bytes')::bigint,0)<524288000 OR (COALESCE((p_runtime->>'total_storage_bytes')::bigint,0)>0 AND (p_runtime->>'free_storage_bytes')::numeric/(p_runtime->>'total_storage_bytes')::numeric<0.10);
  old_low_storage:=FOUND AND (old.free_storage_bytes<524288000 OR (old.total_storage_bytes>0 AND old.free_storage_bytes::numeric/old.total_storage_bytes::numeric<0.10));
  INSERT INTO public.device_runtime_status(device_id,screen_id,owner_id,last_seen_at,session_id,app_version,uptime_ms,available_memory_bytes,memory_low,free_storage_bytes,total_storage_bytes,playback_state,cache_state,sync_state,health_state,last_sync_at,last_error_code,last_error_summary,last_error_at,active_playlist_id,active_playlist_version,active_manifest_etag,previous_playlist_id,current_item_id,current_content_kind,current_media_type,updated_at)
  VALUES(p_device_id,d.screen_id,screen_owner,now_at,p_session_id,p_app_version,(p_runtime->>'uptime_ms')::bigint,(p_runtime->>'available_memory_bytes')::bigint,(p_runtime->>'memory_low')::boolean,(p_runtime->>'free_storage_bytes')::bigint,(p_runtime->>'total_storage_bytes')::bigint,p_runtime->>'playback_state',p_runtime->>'cache_state',p_runtime->>'sync_state',p_runtime->>'health_state',CASE WHEN (p_runtime->>'last_sync_epoch_ms')~'^\d+$' THEN to_timestamp((p_runtime->>'last_sync_epoch_ms')::double precision/1000) END,error_code,error_summary,error_at,p_runtime->>'active_playlist_id',(p_runtime->>'active_playlist_version')::bigint,p_runtime->>'active_manifest_etag',p_runtime->>'previous_playlist_id',p_runtime->>'current_item_id',p_runtime->>'current_content_kind',p_runtime->>'current_media_type',now_at)
  ON CONFLICT(device_id) DO UPDATE SET screen_id=EXCLUDED.screen_id,owner_id=EXCLUDED.owner_id,last_seen_at=EXCLUDED.last_seen_at,session_id=EXCLUDED.session_id,app_version=EXCLUDED.app_version,uptime_ms=EXCLUDED.uptime_ms,available_memory_bytes=EXCLUDED.available_memory_bytes,memory_low=EXCLUDED.memory_low,free_storage_bytes=EXCLUDED.free_storage_bytes,total_storage_bytes=EXCLUDED.total_storage_bytes,playback_state=EXCLUDED.playback_state,cache_state=EXCLUDED.cache_state,sync_state=EXCLUDED.sync_state,health_state=EXCLUDED.health_state,last_sync_at=EXCLUDED.last_sync_at,last_error_code=EXCLUDED.last_error_code,last_error_summary=EXCLUDED.last_error_summary,last_error_at=EXCLUDED.last_error_at,active_playlist_id=EXCLUDED.active_playlist_id,active_playlist_version=EXCLUDED.active_playlist_version,active_manifest_etag=EXCLUDED.active_manifest_etag,previous_playlist_id=EXCLUDED.previous_playlist_id,current_item_id=EXCLUDED.current_item_id,current_content_kind=EXCLUDED.current_content_kind,current_media_type=EXCLUDED.current_media_type,updated_at=now_at RETURNING * INTO fresh;
  IF old.device_id IS NULL OR old.session_id<>fresh.session_id THEN INSERT INTO public.device_health_events(device_id,screen_id,owner_id,event_type,severity,metadata) VALUES(p_device_id,d.screen_id,screen_owner,'PLAYER_SESSION_STARTED','INFO',jsonb_build_object('session_id',fresh.session_id)); END IF;
  IF old.device_id IS NOT NULL THEN
    IF old.health_state<>fresh.health_state THEN INSERT INTO public.device_health_events(device_id,screen_id,owner_id,event_type,severity,metadata) VALUES(p_device_id,d.screen_id,screen_owner,CASE WHEN fresh.health_state='HEALTHY' THEN 'HEALTH_RECOVERED' ELSE 'HEALTH_DEGRADED' END,CASE WHEN fresh.health_state='ERROR' THEN 'ERROR' WHEN fresh.health_state='DEGRADED' THEN 'WARNING' ELSE 'INFO' END,jsonb_build_object('from',old.health_state,'to',fresh.health_state)); END IF;
    IF old.playback_state<>fresh.playback_state AND (old.playback_state='ERROR' OR fresh.playback_state='ERROR') THEN INSERT INTO public.device_health_events(device_id,screen_id,owner_id,event_type,severity) VALUES(p_device_id,d.screen_id,screen_owner,CASE WHEN fresh.playback_state='ERROR' THEN 'PLAYBACK_ERROR' ELSE 'PLAYBACK_RECOVERED' END,CASE WHEN fresh.playback_state='ERROR' THEN 'ERROR' ELSE 'INFO' END); END IF;
    IF old.sync_state<>fresh.sync_state AND (old.sync_state='ERROR' OR fresh.sync_state='ERROR') THEN INSERT INTO public.device_health_events(device_id,screen_id,owner_id,event_type,severity) VALUES(p_device_id,d.screen_id,screen_owner,CASE WHEN fresh.sync_state='ERROR' THEN 'SYNC_FAILED' ELSE 'SYNC_RECOVERED' END,CASE WHEN fresh.sync_state='ERROR' THEN 'WARNING' ELSE 'INFO' END); END IF;
    IF old.cache_state<>fresh.cache_state AND (old.cache_state='ERROR' OR fresh.cache_state='ERROR') THEN INSERT INTO public.device_health_events(device_id,screen_id,owner_id,event_type,severity) VALUES(p_device_id,d.screen_id,screen_owner,CASE WHEN fresh.cache_state='ERROR' THEN 'CACHE_ERROR' ELSE 'CACHE_RECOVERED' END,CASE WHEN fresh.cache_state='ERROR' THEN 'WARNING' ELSE 'INFO' END); END IF;
    IF old_low_storage<>low_storage THEN INSERT INTO public.device_health_events(device_id,screen_id,owner_id,event_type,severity) VALUES(p_device_id,d.screen_id,screen_owner,CASE WHEN low_storage THEN 'LOW_STORAGE' ELSE 'STORAGE_RECOVERED' END,CASE WHEN low_storage THEN 'WARNING' ELSE 'INFO' END); END IF;
    IF old.memory_low<>fresh.memory_low THEN INSERT INTO public.device_health_events(device_id,screen_id,owner_id,event_type,severity) VALUES(p_device_id,d.screen_id,screen_owner,CASE WHEN fresh.memory_low THEN 'LOW_MEMORY' ELSE 'MEMORY_RECOVERED' END,CASE WHEN fresh.memory_low THEN 'WARNING' ELSE 'INFO' END); END IF;
  END IF;
  DELETE FROM public.device_health_events WHERE device_id=p_device_id AND (occurred_at<now()-interval '30 days' OR id IN (SELECT id FROM public.device_health_events WHERE device_id=p_device_id ORDER BY occurred_at DESC OFFSET 100));
  UPDATE public.devices SET last_seen_at=now_at,updated_at=now_at,app_version=p_app_version,metadata=jsonb_build_object('health_state',fresh.health_state,'playback_state',fresh.playback_state,'sync_state',fresh.sync_state) WHERE id=p_device_id;
  RETURN fresh;
EXCEPTION WHEN invalid_text_representation OR numeric_value_out_of_range OR division_by_zero THEN RAISE EXCEPTION 'invalid runtime field';
END;$$;

REVOKE ALL ON FUNCTION public.record_device_runtime_status(UUID,JSONB,TEXT,TEXT,JSONB) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.record_device_runtime_status(UUID,JSONB,TEXT,TEXT,JSONB) TO service_role;

CREATE OR REPLACE FUNCTION public.complete_player_command(p_device_id UUID,p_command_id UUID,p_status TEXT,p_result JSONB)
RETURNS public.device_commands LANGUAGE plpgsql SECURITY DEFINER SET search_path=public AS $$
DECLARE v_command public.device_commands%ROWTYPE;
BEGIN
  SELECT * INTO v_command FROM public.device_commands WHERE id=p_command_id AND device_id=p_device_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'command unavailable'; END IF;
  IF v_command.status IN ('SUCCEEDED','FAILED','EXPIRED') THEN RETURN v_command; END IF;
  IF v_command.expires_at<=now() THEN UPDATE public.device_commands SET status='EXPIRED',completed_at=now(),result='{"code":"expired"}'::jsonb WHERE id=v_command.id RETURNING * INTO v_command;RETURN v_command;END IF;
  IF p_status NOT IN ('SUCCEEDED','FAILED') OR jsonb_typeof(p_result)<>'object' OR octet_length(p_result::text)>4096 THEN RAISE EXCEPTION 'invalid command result'; END IF;
  IF v_command.command_type='GET_STATUS' THEN
    IF EXISTS(SELECT 1 FROM jsonb_object_keys(p_result) k WHERE k NOT IN ('app_version','session_id','uptime_ms','available_memory_bytes','memory_low','free_storage_bytes','total_storage_bytes','connection','playback_state','cache_state','sync_state','health_state','last_sync_epoch_ms','active_playlist_id','active_playlist_version','active_manifest_etag','previous_playlist_id','current_item_id','current_content_kind','current_media_type','last_error_code','last_error_summary','last_error_at_epoch_ms'))
      OR NOT p_result ?& ARRAY['app_version','session_id','uptime_ms','available_memory_bytes','memory_low','free_storage_bytes','total_storage_bytes','connection','playback_state','cache_state','sync_state','health_state','last_sync_epoch_ms','active_playlist_id','active_playlist_version','active_manifest_etag','previous_playlist_id','current_item_id','current_content_kind','current_media_type','last_error_code','last_error_summary','last_error_at_epoch_ms']
    THEN RAISE EXCEPTION 'invalid status result';END IF;
  ELSE
    IF EXISTS(SELECT 1 FROM jsonb_object_keys(p_result) k WHERE k<>'code') OR NOT p_result?'code' OR length(COALESCE(p_result->>'code','')) NOT BETWEEN 1 AND 64 THEN RAISE EXCEPTION 'invalid action result';END IF;
  END IF;
  UPDATE public.device_commands SET status=p_status,completed_at=now(),result=p_result,next_delivery_at=NULL WHERE id=v_command.id RETURNING * INTO v_command;RETURN v_command;
END;$$;
REVOKE ALL ON FUNCTION public.complete_player_command(UUID,UUID,TEXT,JSONB) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.complete_player_command(UUID,UUID,TEXT,JSONB) TO service_role;
