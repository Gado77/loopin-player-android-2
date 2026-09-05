CREATE TABLE public.release_admins (
  user_id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE public.player_releases (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  channel TEXT NOT NULL CHECK(channel IN ('STABLE','BETA')),
  version_code BIGINT,
  version_name TEXT,
  package_name TEXT CHECK(package_name IS NULL OR package_name='com.loopin.player2'),
  apk_size_bytes BIGINT NOT NULL CHECK(apk_size_bytes>0 AND apk_size_bytes<=104857600),
  apk_sha256 TEXT CHECK(apk_sha256 IS NULL OR apk_sha256 ~ '^[a-f0-9]{64}$'),
  storage_path TEXT NOT NULL UNIQUE,
  certificate_sha256 TEXT CHECK(certificate_sha256 IS NULL OR certificate_sha256 ~ '^[a-f0-9]{64}$'),
  status TEXT NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','PUBLISHED','REVOKED')),
  release_notes TEXT CHECK(length(release_notes)<=4000),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  inspected_at TIMESTAMPTZ,
  published_at TIMESTAMPTZ,
  revoked_at TIMESTAMPTZ,
  created_by UUID NOT NULL REFERENCES auth.users(id),
  UNIQUE(package_name,channel,version_code)
);

ALTER TABLE public.devices ADD COLUMN update_channel TEXT NOT NULL DEFAULT 'STABLE' CHECK(update_channel IN ('STABLE','BETA'));
ALTER TABLE public.device_runtime_status ADD COLUMN current_version_code BIGINT;
ALTER TABLE public.device_runtime_status ADD COLUMN update_state TEXT;
ALTER TABLE public.device_runtime_status ADD COLUMN available_version_code BIGINT;
ALTER TABLE public.device_runtime_status ADD COLUMN prepared_version_code BIGINT;
ALTER TABLE public.device_runtime_status ADD COLUMN last_update_check TIMESTAMPTZ;
ALTER TABLE public.device_runtime_status ADD COLUMN last_update_error TEXT;
ALTER TABLE public.device_runtime_status ADD COLUMN installation_capability TEXT;

INSERT INTO storage.buckets(id,name,public,file_size_limit,allowed_mime_types)
VALUES('player2-releases','player2-releases',false,104857600,ARRAY['application/vnd.android.package-archive','application/octet-stream'])
ON CONFLICT(id) DO UPDATE SET public=false,file_size_limit=104857600;

ALTER TABLE public.release_admins ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.player_releases ENABLE ROW LEVEL SECURITY;
CREATE POLICY release_admin_self ON public.release_admins FOR SELECT TO authenticated USING(user_id=auth.uid());
CREATE POLICY releases_read_admin ON public.player_releases FOR SELECT TO authenticated USING(EXISTS(SELECT 1 FROM public.release_admins a WHERE a.user_id=auth.uid()));
REVOKE INSERT,UPDATE,DELETE ON public.player_releases FROM anon,authenticated;
REVOKE INSERT,UPDATE,DELETE ON public.release_admins FROM anon,authenticated;

CREATE OR REPLACE FUNCTION public.set_device_update_channel(p_screen_id UUID,p_channel TEXT) RETURNS TEXT
LANGUAGE plpgsql SECURITY DEFINER SET search_path=public AS $$
DECLARE d UUID;
BEGIN
 IF p_channel NOT IN ('STABLE','BETA') THEN RAISE EXCEPTION 'invalid_channel'; END IF;
 SELECT id INTO d FROM public.devices WHERE screen_id=p_screen_id AND owner_id=auth.uid() AND pairing_status='PAIRED';
 IF d IS NULL THEN RAISE EXCEPTION 'device_not_found'; END IF;
 UPDATE public.devices SET update_channel=p_channel,updated_at=now() WHERE id=d;
 RETURN p_channel;
END$$;
REVOKE ALL ON FUNCTION public.set_device_update_channel(UUID,TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.set_device_update_channel(UUID,TEXT) TO authenticated;

CREATE OR REPLACE FUNCTION public.publish_player_release(p_release_id UUID,p_revoke BOOLEAN DEFAULT false) RETURNS public.player_releases
LANGUAGE plpgsql SECURITY DEFINER SET search_path=public AS $$
DECLARE r public.player_releases;
BEGIN
 IF NOT EXISTS(SELECT 1 FROM public.release_admins WHERE user_id=auth.uid()) THEN RAISE EXCEPTION 'release_admin_required'; END IF;
 SELECT * INTO r FROM public.player_releases WHERE id=p_release_id FOR UPDATE;
 IF r.id IS NULL THEN RAISE EXCEPTION 'release_not_found'; END IF;
 IF p_revoke THEN
   IF r.status<>'PUBLISHED' THEN RAISE EXCEPTION 'only_published_can_be_revoked'; END IF;
   UPDATE public.player_releases SET status='REVOKED',revoked_at=now() WHERE id=r.id RETURNING * INTO r;
 ELSE
   IF r.status<>'DRAFT' OR r.inspected_at IS NULL OR r.version_code IS NULL OR r.apk_sha256 IS NULL OR r.certificate_sha256 IS NULL THEN RAISE EXCEPTION 'release_not_inspected'; END IF;
   UPDATE public.player_releases SET status='PUBLISHED',published_at=now() WHERE id=r.id RETURNING * INTO r;
 END IF;
 RETURN r;
END$$;
REVOKE ALL ON FUNCTION public.publish_player_release(UUID,BOOLEAN) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.publish_player_release(UUID,BOOLEAN) TO authenticated;

CREATE OR REPLACE FUNCTION public.prevent_published_release_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status IN ('PUBLISHED','REVOKED') AND NOT (OLD.status='PUBLISHED' AND NEW.status='REVOKED' AND NEW.id=OLD.id AND NEW.storage_path=OLD.storage_path AND NEW.version_code=OLD.version_code AND NEW.apk_sha256=OLD.apk_sha256 AND NEW.certificate_sha256=OLD.certificate_sha256) THEN RAISE EXCEPTION 'published_release_immutable'; END IF;
 RETURN NEW;
END$$;
CREATE TRIGGER player_release_immutable BEFORE UPDATE ON public.player_releases FOR EACH ROW EXECUTE FUNCTION public.prevent_published_release_mutation();

CREATE INDEX player_releases_lookup ON public.player_releases(package_name,channel,status,version_code DESC);

ALTER TABLE public.device_commands DROP CONSTRAINT device_commands_command_type_check;
ALTER TABLE public.device_commands ADD CONSTRAINT device_commands_command_type_check CHECK(command_type IN ('GET_STATUS','SYNC_NOW','RELOAD_PLAYLIST','CHECK_UPDATE'));
CREATE OR REPLACE FUNCTION public.enqueue_player_command(p_screen_id UUID,p_command_type TEXT,p_payload JSONB DEFAULT NULL) RETURNS public.device_commands
LANGUAGE plpgsql SECURITY DEFINER SET search_path=public AS $$
DECLARE v_owner UUID:=auth.uid();v_device UUID;v_result public.device_commands%ROWTYPE;
BEGIN
 IF v_owner IS NULL THEN RAISE EXCEPTION 'authentication required';END IF;
 IF p_command_type NOT IN ('GET_STATUS','SYNC_NOW','RELOAD_PLAYLIST','CHECK_UPDATE') THEN RAISE EXCEPTION 'unsupported command';END IF;
 IF p_payload IS NOT NULL AND p_payload<>'{}'::jsonb THEN RAISE EXCEPTION 'payload is not accepted';END IF;
 SELECT d.id INTO v_device FROM public.screens s JOIN public.devices d ON d.screen_id=s.id WHERE s.id=p_screen_id AND s.owner_id=v_owner AND s.status='ACTIVE' AND d.pairing_status='PAIRED';
 IF v_device IS NULL THEN RAISE EXCEPTION 'paired player unavailable';END IF;
 INSERT INTO public.device_commands(device_id,screen_id,owner_id,command_type,payload,expires_at)VALUES(v_device,p_screen_id,v_owner,p_command_type,'{}',now()+interval '15 minutes')RETURNING * INTO v_result;RETURN v_result;
END$$;
REVOKE ALL ON FUNCTION public.enqueue_player_command(UUID,TEXT,JSONB) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.enqueue_player_command(UUID,TEXT,JSONB) TO authenticated;

CREATE OR REPLACE FUNCTION public.complete_player_command(p_device_id UUID,p_command_id UUID,p_status TEXT,p_result JSONB) RETURNS public.device_commands
LANGUAGE plpgsql SECURITY DEFINER SET search_path=public AS $$
DECLARE c public.device_commands%ROWTYPE;
BEGIN
 SELECT * INTO c FROM public.device_commands WHERE id=p_command_id AND device_id=p_device_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'command unavailable';END IF;IF c.status IN('SUCCEEDED','FAILED','EXPIRED')THEN RETURN c;END IF;
 IF p_status NOT IN('SUCCEEDED','FAILED')OR jsonb_typeof(p_result)<>'object'OR octet_length(p_result::text)>4096 THEN RAISE EXCEPTION 'invalid command result';END IF;
 IF c.command_type='GET_STATUS' THEN
  IF EXISTS(SELECT 1 FROM jsonb_object_keys(p_result)k WHERE k NOT IN('app_version','session_id','uptime_ms','available_memory_bytes','memory_low','free_storage_bytes','total_storage_bytes','connection','playback_state','cache_state','sync_state','health_state','last_sync_epoch_ms','active_playlist_id','active_playlist_version','active_manifest_etag','previous_playlist_id','current_item_id','current_content_kind','current_media_type','last_error_code','last_error_summary','last_error_at_epoch_ms','update_channel','current_version_code','update_state','available_version_code','prepared_version_code','last_update_check_epoch_ms','last_update_error','installation_capability'))THEN RAISE EXCEPTION 'invalid status result';END IF;
 ELSE IF EXISTS(SELECT 1 FROM jsonb_object_keys(p_result)k WHERE k<>'code')OR NOT p_result?'code'THEN RAISE EXCEPTION 'invalid action result';END IF;END IF;
 UPDATE public.device_commands SET status=p_status,completed_at=now(),result=p_result,next_delivery_at=NULL WHERE id=c.id RETURNING * INTO c;RETURN c;
END$$;
REVOKE ALL ON FUNCTION public.complete_player_command(UUID,UUID,TEXT,JSONB) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.complete_player_command(UUID,UUID,TEXT,JSONB) TO service_role;
