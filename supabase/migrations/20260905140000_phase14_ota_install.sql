CREATE TABLE public.device_update_attempts(
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), device_id UUID NOT NULL REFERENCES public.devices(id) ON DELETE CASCADE,
 screen_id UUID NOT NULL REFERENCES public.screens(id) ON DELETE CASCADE, owner_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
 release_id UUID NOT NULL REFERENCES public.player_releases(id), from_version_code BIGINT NOT NULL, target_version_code BIGINT NOT NULL,
 state TEXT NOT NULL CHECK(state IN('INSTALL_REQUESTED','INSTALL_PERMISSION_REQUIRED','USER_ACTION_REQUIRED','INSTALLING','POST_UPDATE_VERIFYING','INSTALLED','INSTALL_DEFERRED','INSTALL_CANCELED','INSTALL_FAILED','UPDATE_RECOVERY_REQUIRED')),
 requested_at TIMESTAMPTZ NOT NULL DEFAULT now(), install_started_at TIMESTAMPTZ, first_seen_target_at TIMESTAMPTZ, completed_at TIMESTAMPTZ,
 failure_code TEXT CHECK(failure_code IS NULL OR length(failure_code)<=64), UNIQUE(device_id,release_id)
);
ALTER TABLE public.device_update_attempts ENABLE ROW LEVEL SECURITY;
CREATE POLICY device_update_attempts_owner_read ON public.device_update_attempts FOR SELECT TO authenticated USING(owner_id=auth.uid());
REVOKE INSERT,UPDATE,DELETE ON public.device_update_attempts FROM anon,authenticated;
CREATE INDEX device_update_attempts_screen_time ON public.device_update_attempts(screen_id,requested_at DESC);

ALTER TABLE public.device_runtime_status ADD COLUMN installation_state TEXT;
ALTER TABLE public.device_runtime_status ADD COLUMN install_requested_at TIMESTAMPTZ;
ALTER TABLE public.device_runtime_status ADD COLUMN post_update_verification_state TEXT;
ALTER TABLE public.device_runtime_status ADD COLUMN last_install_failure_code TEXT;

ALTER TABLE public.device_commands DROP CONSTRAINT device_commands_command_type_check;
ALTER TABLE public.device_commands ADD CONSTRAINT device_commands_command_type_check CHECK(command_type IN ('GET_STATUS','SYNC_NOW','RELOAD_PLAYLIST','CHECK_UPDATE','INSTALL_UPDATE'));

CREATE OR REPLACE FUNCTION public.enqueue_player_command(p_screen_id UUID,p_command_type TEXT,p_payload JSONB DEFAULT NULL) RETURNS public.device_commands
LANGUAGE plpgsql SECURITY DEFINER SET search_path=public AS $$
DECLARE v_owner UUID:=auth.uid();v_device UUID;v_result public.device_commands%ROWTYPE;v_runtime public.device_runtime_status%ROWTYPE;v_release public.player_releases%ROWTYPE;
BEGIN
 IF v_owner IS NULL THEN RAISE EXCEPTION 'authentication required';END IF;
 IF p_command_type NOT IN ('GET_STATUS','SYNC_NOW','RELOAD_PLAYLIST','CHECK_UPDATE','INSTALL_UPDATE') THEN RAISE EXCEPTION 'unsupported command';END IF;
 IF p_payload IS NOT NULL AND p_payload<>'{}'::jsonb THEN RAISE EXCEPTION 'payload is not accepted';END IF;
 SELECT d.id INTO v_device FROM public.screens s JOIN public.devices d ON d.screen_id=s.id WHERE s.id=p_screen_id AND s.owner_id=v_owner AND s.status='ACTIVE' AND d.pairing_status='PAIRED';
 IF v_device IS NULL THEN RAISE EXCEPTION 'paired player unavailable';END IF;
 IF p_command_type='INSTALL_UPDATE' THEN
  SELECT * INTO v_runtime FROM public.device_runtime_status WHERE device_id=v_device;
  IF v_runtime.update_state<>'READY_TO_INSTALL' OR v_runtime.prepared_version_code IS NULL OR v_runtime.prepared_version_code<=COALESCE(v_runtime.current_version_code,0) THEN RAISE EXCEPTION 'no prepared update';END IF;
  SELECT r.* INTO v_release FROM public.player_releases r JOIN public.devices d ON d.id=v_device AND d.update_channel=r.channel WHERE r.status='PUBLISHED' AND r.package_name='com.loopin.player2' AND r.version_code=v_runtime.prepared_version_code;
  IF v_release.id IS NULL THEN RAISE EXCEPTION 'update not authorized';END IF;
  INSERT INTO public.device_update_attempts(device_id,screen_id,owner_id,release_id,from_version_code,target_version_code,state)
  VALUES(v_device,p_screen_id,v_owner,v_release.id,COALESCE(v_runtime.current_version_code,0),v_release.version_code,'INSTALL_REQUESTED')
  ON CONFLICT(device_id,release_id) DO UPDATE SET state=CASE WHEN device_update_attempts.state='INSTALLED' THEN device_update_attempts.state ELSE 'INSTALL_REQUESTED' END,requested_at=now(),failure_code=NULL;
 END IF;
 INSERT INTO public.device_commands(device_id,screen_id,owner_id,command_type,payload,expires_at)VALUES(v_device,p_screen_id,v_owner,p_command_type,'{}',now()+interval '15 minutes')RETURNING * INTO v_result;RETURN v_result;
END$$;
REVOKE ALL ON FUNCTION public.enqueue_player_command(UUID,TEXT,JSONB) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.enqueue_player_command(UUID,TEXT,JSONB) TO authenticated;

CREATE OR REPLACE FUNCTION public.report_device_update_attempt(p_device_id UUID,p_release_id UUID,p_state TEXT,p_failure_code TEXT,p_current_version BIGINT) RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER SET search_path=public AS $$
DECLARE a public.device_update_attempts%ROWTYPE; allowed BOOLEAN:=false;
BEGIN
 SELECT * INTO a FROM public.device_update_attempts WHERE device_id=p_device_id AND release_id=p_release_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'attempt unavailable';END IF;
 IF p_failure_code IS NOT NULL AND length(p_failure_code)>64 THEN RAISE EXCEPTION 'invalid failure';END IF;
 allowed := p_state=a.state OR
  (a.state='INSTALL_REQUESTED' AND p_state IN('INSTALL_PERMISSION_REQUIRED','USER_ACTION_REQUIRED','INSTALLING','INSTALL_FAILED')) OR
  (a.state IN('INSTALL_PERMISSION_REQUIRED','USER_ACTION_REQUIRED','INSTALL_DEFERRED','INSTALL_CANCELED','INSTALL_FAILED') AND p_state IN('INSTALLING','USER_ACTION_REQUIRED','INSTALL_FAILED','INSTALL_CANCELED','INSTALL_DEFERRED')) OR
  (a.state='INSTALLING' AND p_state IN('POST_UPDATE_VERIFYING','INSTALL_FAILED','INSTALL_CANCELED')) OR
  (a.state='POST_UPDATE_VERIFYING' AND p_state IN('INSTALLED','UPDATE_RECOVERY_REQUIRED'));
 IF NOT allowed THEN RAISE EXCEPTION 'invalid transition';END IF;
 IF p_state='INSTALLED' AND p_current_version<>a.target_version_code THEN RAISE EXCEPTION 'target version mismatch';END IF;
 UPDATE public.device_update_attempts SET state=p_state,failure_code=p_failure_code,
  install_started_at=CASE WHEN p_state='INSTALLING' THEN COALESCE(install_started_at,now()) ELSE install_started_at END,
  first_seen_target_at=CASE WHEN p_state IN('POST_UPDATE_VERIFYING','INSTALLED') THEN COALESCE(first_seen_target_at,now()) ELSE first_seen_target_at END,
  completed_at=CASE WHEN p_state IN('INSTALLED','INSTALL_FAILED','INSTALL_CANCELED','UPDATE_RECOVERY_REQUIRED') THEN COALESCE(completed_at,now()) ELSE completed_at END
 WHERE id=a.id;
END$$;
REVOKE ALL ON FUNCTION public.report_device_update_attempt(UUID,UUID,TEXT,TEXT,BIGINT) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.report_device_update_attempt(UUID,UUID,TEXT,TEXT,BIGINT) TO service_role;

CREATE OR REPLACE FUNCTION public.complete_player_command(p_device_id UUID,p_command_id UUID,p_status TEXT,p_result JSONB) RETURNS public.device_commands
LANGUAGE plpgsql SECURITY DEFINER SET search_path=public AS $$
DECLARE c public.device_commands%ROWTYPE;
BEGIN
 SELECT * INTO c FROM public.device_commands WHERE id=p_command_id AND device_id=p_device_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'command unavailable';END IF;IF c.status IN('SUCCEEDED','FAILED','EXPIRED')THEN RETURN c;END IF;
 IF p_status NOT IN('SUCCEEDED','FAILED')OR jsonb_typeof(p_result)<>'object'OR octet_length(p_result::text)>4096 THEN RAISE EXCEPTION 'invalid command result';END IF;
 IF c.command_type='GET_STATUS' THEN
  IF EXISTS(SELECT 1 FROM jsonb_object_keys(p_result)k WHERE k NOT IN('app_version','session_id','uptime_ms','available_memory_bytes','memory_low','free_storage_bytes','total_storage_bytes','connection','playback_state','cache_state','sync_state','health_state','last_sync_epoch_ms','active_playlist_id','active_playlist_version','active_manifest_etag','previous_playlist_id','current_item_id','current_content_kind','current_media_type','last_error_code','last_error_summary','last_error_at_epoch_ms','update_channel','current_version_code','update_state','available_version_code','prepared_version_code','last_update_check_epoch_ms','last_update_error','installation_capability','installation_state','install_requested_at_epoch_ms','post_update_verification_state','last_install_failure_code'))THEN RAISE EXCEPTION 'invalid status result';END IF;
 ELSE IF EXISTS(SELECT 1 FROM jsonb_object_keys(p_result)k WHERE k<>'code')OR NOT p_result?'code'THEN RAISE EXCEPTION 'invalid action result';END IF;END IF;
 UPDATE public.device_commands SET status=p_status,completed_at=now(),result=p_result,next_delivery_at=NULL WHERE id=c.id RETURNING * INTO c;RETURN c;
END$$;
REVOKE ALL ON FUNCTION public.complete_player_command(UUID,UUID,TEXT,JSONB) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.complete_player_command(UUID,UUID,TEXT,JSONB) TO service_role;
