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
