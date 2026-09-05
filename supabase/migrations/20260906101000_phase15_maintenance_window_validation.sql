CREATE OR REPLACE FUNCTION public.create_update_rollout(p_release_id UUID,p_name TEXT,p_group_ids UUID[],p_screen_ids UUID[],p_waves INTEGER[],p_scheduled_start_at TIMESTAMPTZ,p_timezone TEXT,p_window_start TIME,p_window_end TIME) RETURNS public.update_rollouts
LANGUAGE plpgsql SECURITY DEFINER SET search_path=public AS $$ DECLARE r public.update_rollouts;gid UUID;sid UUID;BEGIN
 IF NOT EXISTS(SELECT 1 FROM release_admins WHERE user_id=auth.uid())THEN RAISE EXCEPTION 'release_admin_required';END IF;
 IF NOT EXISTS(SELECT 1 FROM player_releases WHERE id=p_release_id AND status='PUBLISHED')THEN RAISE EXCEPTION 'published release required';END IF;
 IF COALESCE(array_length(p_group_ids,1),0)+COALESCE(array_length(p_screen_ids,1),0)=0 THEN RAISE EXCEPTION 'targets required';END IF;
 IF NOT valid_rollout_waves(p_waves)THEN RAISE EXCEPTION 'invalid waves';END IF;
 IF (p_timezone IS NULL)<>(p_window_start IS NULL) OR (p_timezone IS NULL)<>(p_window_end IS NULL)
    OR (p_window_start IS NOT NULL AND p_window_start=p_window_end)
    OR (p_timezone IS NOT NULL AND NOT EXISTS(SELECT 1 FROM pg_timezone_names WHERE name=p_timezone))
 THEN RAISE EXCEPTION 'invalid maintenance window';END IF;
 INSERT INTO update_rollouts(owner_id,release_id,created_by,name,waves,scheduled_start_at,maintenance_timezone,maintenance_start_local,maintenance_end_local)
 VALUES(auth.uid(),p_release_id,auth.uid(),trim(p_name),p_waves,p_scheduled_start_at,p_timezone,p_window_start,p_window_end)RETURNING * INTO r;
 FOREACH gid IN ARRAY COALESCE(p_group_ids,ARRAY[]::UUID[]) LOOP IF NOT EXISTS(SELECT 1 FROM screen_groups WHERE id=gid AND owner_id=auth.uid())THEN RAISE EXCEPTION 'group unavailable';END IF;INSERT INTO update_rollout_group_targets VALUES(r.id,gid);END LOOP;
 FOREACH sid IN ARRAY COALESCE(p_screen_ids,ARRAY[]::UUID[]) LOOP IF NOT EXISTS(SELECT 1 FROM screens WHERE id=sid AND owner_id=auth.uid())THEN RAISE EXCEPTION 'screen unavailable';END IF;INSERT INTO update_rollout_screen_targets VALUES(r.id,sid);END LOOP;
 RETURN r;END$$;
REVOKE ALL ON FUNCTION public.create_update_rollout(UUID,TEXT,UUID[],UUID[],INTEGER[],TIMESTAMPTZ,TEXT,TIME,TIME) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.create_update_rollout(UUID,TEXT,UUID[],UUID[],INTEGER[],TIMESTAMPTZ,TEXT,TIME,TIME) TO authenticated;
