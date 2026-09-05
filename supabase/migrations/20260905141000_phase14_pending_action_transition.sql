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
  (a.state='INSTALLING' AND p_state IN('USER_ACTION_REQUIRED','POST_UPDATE_VERIFYING','INSTALL_FAILED','INSTALL_CANCELED')) OR
  (a.state='POST_UPDATE_VERIFYING' AND p_state IN('INSTALLED','UPDATE_RECOVERY_REQUIRED'));
 IF NOT allowed THEN RAISE EXCEPTION 'invalid transition';END IF;
 IF p_state='INSTALLED' AND p_current_version<>a.target_version_code THEN RAISE EXCEPTION 'target version mismatch';END IF;
 UPDATE public.device_update_attempts SET state=p_state,failure_code=p_failure_code,
  install_started_at=CASE WHEN p_state='INSTALLING' THEN COALESCE(install_started_at,now()) ELSE install_started_at END,
  first_seen_target_at=CASE WHEN p_state IN('POST_UPDATE_VERIFYING','INSTALLED') THEN COALESCE(first_seen_target_at,now()) ELSE first_seen_target_at END,
  completed_at=CASE WHEN p_state IN('INSTALLED','INSTALL_FAILED','INSTALL_CANCELED','UPDATE_RECOVERY_REQUIRED') THEN COALESCE(completed_at,now()) ELSE completed_at END
 WHERE id=a.id;
END$$;
