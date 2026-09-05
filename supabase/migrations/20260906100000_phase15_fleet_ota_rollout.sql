CREATE TABLE public.screen_groups(
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), owner_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
 name TEXT NOT NULL CHECK(length(trim(name)) BETWEEN 1 AND 100), description TEXT CHECK(description IS NULL OR length(description)<=500),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE public.screen_group_members(
 group_id UUID NOT NULL REFERENCES public.screen_groups(id) ON DELETE CASCADE,
 screen_id UUID NOT NULL REFERENCES public.screens(id) ON DELETE CASCADE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY(group_id,screen_id)
);
CREATE TABLE public.update_rollouts(
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), owner_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
 release_id UUID NOT NULL REFERENCES public.player_releases(id), created_by UUID NOT NULL REFERENCES auth.users(id), name TEXT NOT NULL CHECK(length(trim(name)) BETWEEN 1 AND 120),
 status TEXT NOT NULL DEFAULT 'DRAFT' CHECK(status IN('DRAFT','SCHEDULED','ACTIVE','PAUSED','PAUSED_AUTO','COMPLETED','CANCELED')),
 waves INTEGER[] NOT NULL DEFAULT ARRAY[5,25,100], current_wave INTEGER NOT NULL DEFAULT 1 CHECK(current_wave>0),
 scheduled_start_at TIMESTAMPTZ, maintenance_timezone TEXT, maintenance_start_local TIME, maintenance_end_local TIME,
 failure_threshold_percent NUMERIC(5,2) NOT NULL DEFAULT 30 CHECK(failure_threshold_percent BETWEEN 1 AND 100),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), started_at TIMESTAMPTZ, paused_at TIMESTAMPTZ, resumed_at TIMESTAMPTZ,
 advanced_at TIMESTAMPTZ, completed_at TIMESTAMPTZ, canceled_at TIMESTAMPTZ, updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE public.update_rollout_group_targets(rollout_id UUID NOT NULL REFERENCES public.update_rollouts(id) ON DELETE CASCADE,group_id UUID NOT NULL REFERENCES public.screen_groups(id) ON DELETE CASCADE,PRIMARY KEY(rollout_id,group_id));
CREATE TABLE public.update_rollout_screen_targets(rollout_id UUID NOT NULL REFERENCES public.update_rollouts(id) ON DELETE CASCADE,screen_id UUID NOT NULL REFERENCES public.screens(id) ON DELETE CASCADE,PRIMARY KEY(rollout_id,screen_id));
CREATE TABLE public.update_rollout_devices(
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), rollout_id UUID NOT NULL REFERENCES public.update_rollouts(id) ON DELETE CASCADE,
 screen_id UUID NOT NULL REFERENCES public.screens(id) ON DELETE CASCADE, device_id UUID REFERENCES public.devices(id) ON DELETE SET NULL,
 cohort_score INTEGER CHECK(cohort_score BETWEEN 0 AND 9999), state TEXT NOT NULL DEFAULT 'PENDING' CHECK(state IN('PENDING','NOT_IN_CURRENT_WAVE','WAITING_WINDOW','AVAILABLE','DOWNLOADING','READY','INSTALLING','INSTALLED','FAILED','CHANNEL_MISMATCH','NO_DEVICE')),
 first_check_at TIMESTAMPTZ, ready_at TIMESTAMPTZ, installed_at TIMESTAMPTZ, failure_code TEXT CHECK(failure_code IS NULL OR length(failure_code)<=64),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(rollout_id,screen_id), UNIQUE(rollout_id,device_id)
);

ALTER TABLE public.screen_groups ENABLE ROW LEVEL SECURITY;ALTER TABLE public.screen_group_members ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.update_rollouts ENABLE ROW LEVEL SECURITY;ALTER TABLE public.update_rollout_group_targets ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.update_rollout_screen_targets ENABLE ROW LEVEL SECURITY;ALTER TABLE public.update_rollout_devices ENABLE ROW LEVEL SECURITY;
CREATE POLICY groups_owner_all ON public.screen_groups FOR ALL TO authenticated USING(owner_id=auth.uid()) WITH CHECK(owner_id=auth.uid());
CREATE POLICY group_members_owner_read ON public.screen_group_members FOR SELECT TO authenticated USING(EXISTS(SELECT 1 FROM public.screen_groups g WHERE g.id=group_id AND g.owner_id=auth.uid()));
CREATE POLICY rollouts_owner_read ON public.update_rollouts FOR SELECT TO authenticated USING(owner_id=auth.uid());
CREATE POLICY rollout_group_targets_owner_read ON public.update_rollout_group_targets FOR SELECT TO authenticated USING(EXISTS(SELECT 1 FROM public.update_rollouts r WHERE r.id=rollout_id AND r.owner_id=auth.uid()));
CREATE POLICY rollout_screen_targets_owner_read ON public.update_rollout_screen_targets FOR SELECT TO authenticated USING(EXISTS(SELECT 1 FROM public.update_rollouts r WHERE r.id=rollout_id AND r.owner_id=auth.uid()));
CREATE POLICY rollout_devices_owner_read ON public.update_rollout_devices FOR SELECT TO authenticated USING(EXISTS(SELECT 1 FROM public.update_rollouts r WHERE r.id=rollout_id AND r.owner_id=auth.uid()));
REVOKE INSERT,UPDATE,DELETE ON public.screen_group_members,public.update_rollouts,public.update_rollout_group_targets,public.update_rollout_screen_targets,public.update_rollout_devices FROM anon,authenticated;

CREATE INDEX screen_group_members_screen ON public.screen_group_members(screen_id);CREATE INDEX rollout_status_release ON public.update_rollouts(status,release_id);
CREATE INDEX rollout_devices_device ON public.update_rollout_devices(device_id,rollout_id);CREATE INDEX rollout_devices_rollout_state ON public.update_rollout_devices(rollout_id,state);

CREATE OR REPLACE FUNCTION public.set_screen_group(p_group_id UUID,p_name TEXT,p_description TEXT,p_screen_ids UUID[]) RETURNS public.screen_groups
LANGUAGE plpgsql SECURITY DEFINER SET search_path=public AS $$ DECLARE g public.screen_groups;sid UUID;BEGIN
 IF auth.uid() IS NULL OR length(trim(p_name)) NOT BETWEEN 1 AND 100 OR COALESCE(array_length(p_screen_ids,1),0)>500 THEN RAISE EXCEPTION 'invalid group';END IF;
 IF p_group_id IS NULL THEN INSERT INTO screen_groups(owner_id,name,description)VALUES(auth.uid(),trim(p_name),nullif(trim(p_description),''))RETURNING * INTO g;
 ELSE UPDATE screen_groups SET name=trim(p_name),description=nullif(trim(p_description),''),updated_at=now() WHERE id=p_group_id AND owner_id=auth.uid() RETURNING * INTO g;IF g.id IS NULL THEN RAISE EXCEPTION 'group unavailable';END IF;DELETE FROM screen_group_members WHERE group_id=g.id;END IF;
 FOREACH sid IN ARRAY COALESCE(p_screen_ids,ARRAY[]::UUID[]) LOOP
  IF NOT EXISTS(SELECT 1 FROM screens WHERE id=sid AND owner_id=auth.uid())THEN RAISE EXCEPTION 'screen unavailable';END IF;
  INSERT INTO screen_group_members(group_id,screen_id)VALUES(g.id,sid)ON CONFLICT DO NOTHING;
 END LOOP;RETURN g;END$$;
REVOKE ALL ON FUNCTION public.set_screen_group(UUID,TEXT,TEXT,UUID[]) FROM PUBLIC,anon;GRANT EXECUTE ON FUNCTION public.set_screen_group(UUID,TEXT,TEXT,UUID[]) TO authenticated;

CREATE OR REPLACE FUNCTION public.valid_rollout_waves(v INTEGER[]) RETURNS BOOLEAN LANGUAGE sql IMMUTABLE AS $$
 SELECT COALESCE(array_length(v,1),0) BETWEEN 1 AND 10 AND v[array_length(v,1)]=100 AND NOT EXISTS(SELECT 1 FROM generate_subscripts(v,1)i WHERE v[i]<1 OR v[i]>100 OR (i>1 AND v[i]<=v[i-1]));$$;

CREATE OR REPLACE FUNCTION public.create_update_rollout(p_release_id UUID,p_name TEXT,p_group_ids UUID[],p_screen_ids UUID[],p_waves INTEGER[],p_scheduled_start_at TIMESTAMPTZ,p_timezone TEXT,p_window_start TIME,p_window_end TIME) RETURNS public.update_rollouts
LANGUAGE plpgsql SECURITY DEFINER SET search_path=public AS $$ DECLARE r public.update_rollouts;gid UUID;sid UUID;BEGIN
 IF NOT EXISTS(SELECT 1 FROM release_admins WHERE user_id=auth.uid())THEN RAISE EXCEPTION 'release_admin_required';END IF;
 IF NOT EXISTS(SELECT 1 FROM player_releases WHERE id=p_release_id AND status='PUBLISHED')THEN RAISE EXCEPTION 'published release required';END IF;
 IF COALESCE(array_length(p_group_ids,1),0)+COALESCE(array_length(p_screen_ids,1),0)=0 THEN RAISE EXCEPTION 'targets required';END IF;
 IF NOT valid_rollout_waves(p_waves)THEN RAISE EXCEPTION 'invalid waves';END IF;
 IF (p_window_start IS NULL)<>(p_window_end IS NULL) OR ((p_window_start IS NOT NULL) AND NOT EXISTS(SELECT 1 FROM pg_timezone_names WHERE name=p_timezone))THEN RAISE EXCEPTION 'invalid maintenance window';END IF;
 INSERT INTO update_rollouts(owner_id,release_id,created_by,name,waves,scheduled_start_at,maintenance_timezone,maintenance_start_local,maintenance_end_local)
 VALUES(auth.uid(),p_release_id,auth.uid(),trim(p_name),p_waves,p_scheduled_start_at,p_timezone,p_window_start,p_window_end)RETURNING * INTO r;
 FOREACH gid IN ARRAY COALESCE(p_group_ids,ARRAY[]::UUID[]) LOOP IF NOT EXISTS(SELECT 1 FROM screen_groups WHERE id=gid AND owner_id=auth.uid())THEN RAISE EXCEPTION 'group unavailable';END IF;INSERT INTO update_rollout_group_targets VALUES(r.id,gid);END LOOP;
 FOREACH sid IN ARRAY COALESCE(p_screen_ids,ARRAY[]::UUID[]) LOOP IF NOT EXISTS(SELECT 1 FROM screens WHERE id=sid AND owner_id=auth.uid())THEN RAISE EXCEPTION 'screen unavailable';END IF;INSERT INTO update_rollout_screen_targets VALUES(r.id,sid);END LOOP;
 RETURN r;END$$;
REVOKE ALL ON FUNCTION public.create_update_rollout(UUID,TEXT,UUID[],UUID[],INTEGER[],TIMESTAMPTZ,TEXT,TIME,TIME) FROM PUBLIC,anon;GRANT EXECUTE ON FUNCTION public.create_update_rollout(UUID,TEXT,UUID[],UUID[],INTEGER[],TIMESTAMPTZ,TEXT,TIME,TIME) TO authenticated;

CREATE OR REPLACE FUNCTION public.activate_update_rollout(p_rollout_id UUID) RETURNS public.update_rollouts
LANGUAGE plpgsql SECURITY DEFINER SET search_path=public AS $$ DECLARE r public.update_rollouts;rel public.player_releases;conflicts INTEGER;BEGIN
 IF NOT EXISTS(SELECT 1 FROM release_admins WHERE user_id=auth.uid())THEN RAISE EXCEPTION 'release_admin_required';END IF;
 SELECT * INTO r FROM update_rollouts WHERE id=p_rollout_id AND owner_id=auth.uid() AND status='DRAFT' FOR UPDATE;IF r.id IS NULL THEN RAISE EXCEPTION 'draft unavailable';END IF;
 SELECT * INTO rel FROM player_releases WHERE id=r.release_id AND status='PUBLISHED';IF rel.id IS NULL THEN RAISE EXCEPTION 'published release required';END IF;
 CREATE TEMP TABLE rollout_target_screens(id UUID PRIMARY KEY)ON COMMIT DROP;
 INSERT INTO rollout_target_screens SELECT screen_id FROM update_rollout_screen_targets WHERE rollout_id=r.id ON CONFLICT DO NOTHING;
 INSERT INTO rollout_target_screens SELECT m.screen_id FROM update_rollout_group_targets t JOIN screen_group_members m ON m.group_id=t.group_id WHERE t.rollout_id=r.id ON CONFLICT DO NOTHING;
 IF NOT EXISTS(SELECT 1 FROM rollout_target_screens)THEN RAISE EXCEPTION 'targets required';END IF;
 IF EXISTS(SELECT 1 FROM rollout_target_screens t JOIN screens s ON s.id=t.id WHERE s.owner_id<>r.owner_id)THEN RAISE EXCEPTION 'cross tenant target';END IF;
 SELECT count(*) INTO conflicts FROM rollout_target_screens t JOIN devices d ON d.screen_id=t.id JOIN update_rollout_devices rd ON rd.device_id=d.id JOIN update_rollouts other ON other.id=rd.rollout_id WHERE other.id<>r.id AND other.status IN('ACTIVE','PAUSED','PAUSED_AUTO','SCHEDULED');
 IF conflicts>0 THEN RAISE EXCEPTION 'device rollout conflict';END IF;
 INSERT INTO update_rollout_devices(rollout_id,screen_id,device_id,cohort_score,state)
 SELECT r.id,t.id,d.id,CASE WHEN d.id IS NULL THEN NULL ELSE ((('x'||substr(md5(r.id::text||d.id::text),1,8))::bit(32)::bigint)%10000)::int END,
 CASE WHEN d.id IS NULL THEN 'NO_DEVICE' WHEN d.update_channel<>rel.channel THEN 'CHANNEL_MISMATCH' ELSE 'PENDING' END
 FROM rollout_target_screens t LEFT JOIN devices d ON d.screen_id=t.id AND d.pairing_status='PAIRED';
 UPDATE update_rollouts SET status=CASE WHEN scheduled_start_at IS NOT NULL AND scheduled_start_at>now() THEN 'SCHEDULED' ELSE 'ACTIVE' END,started_at=CASE WHEN scheduled_start_at IS NULL OR scheduled_start_at<=now() THEN now() END,updated_at=now() WHERE id=r.id RETURNING * INTO r;RETURN r;END$$;
REVOKE ALL ON FUNCTION public.activate_update_rollout(UUID) FROM PUBLIC,anon;GRANT EXECUTE ON FUNCTION public.activate_update_rollout(UUID) TO authenticated;

CREATE OR REPLACE FUNCTION public.control_update_rollout(p_rollout_id UUID,p_action TEXT) RETURNS public.update_rollouts
LANGUAGE plpgsql SECURITY DEFINER SET search_path=public AS $$ DECLARE r public.update_rollouts;BEGIN
 IF NOT EXISTS(SELECT 1 FROM release_admins WHERE user_id=auth.uid())THEN RAISE EXCEPTION 'release_admin_required';END IF;SELECT * INTO r FROM update_rollouts WHERE id=p_rollout_id AND owner_id=auth.uid() FOR UPDATE;IF r.id IS NULL THEN RAISE EXCEPTION 'rollout unavailable';END IF;
 IF p_action='PAUSE' AND r.status='ACTIVE' THEN UPDATE update_rollouts SET status='PAUSED',paused_at=now(),updated_at=now()WHERE id=r.id;
 ELSIF p_action='RESUME' AND r.status IN('PAUSED','PAUSED_AUTO') THEN UPDATE update_rollouts SET status='ACTIVE',resumed_at=now(),updated_at=now()WHERE id=r.id;
 ELSIF p_action='ADVANCE' AND r.status='ACTIVE' AND r.current_wave<array_length(r.waves,1) THEN UPDATE update_rollouts SET current_wave=current_wave+1,advanced_at=now(),updated_at=now()WHERE id=r.id;
 ELSIF p_action='CANCEL' AND r.status IN('DRAFT','SCHEDULED','ACTIVE','PAUSED','PAUSED_AUTO') THEN UPDATE update_rollouts SET status='CANCELED',canceled_at=now(),updated_at=now()WHERE id=r.id;
 ELSE RAISE EXCEPTION 'invalid rollout transition';END IF;SELECT * INTO r FROM update_rollouts WHERE id=r.id;RETURN r;END$$;
REVOKE ALL ON FUNCTION public.control_update_rollout(UUID,TEXT) FROM PUBLIC,anon;GRANT EXECUTE ON FUNCTION public.control_update_rollout(UUID,TEXT) TO authenticated;

CREATE OR REPLACE FUNCTION public.get_eligible_player_release(p_device_id UUID,p_current_version BIGINT,p_release_id UUID DEFAULT NULL)
RETURNS TABLE(rollout_id UUID,release_id UUID,channel TEXT,version_code BIGINT,version_name TEXT,package_name TEXT,apk_size_bytes BIGINT,apk_sha256 TEXT,certificate_sha256 TEXT,release_notes TEXT)
LANGUAGE plpgsql SECURITY DEFINER SET search_path=public AS $$ DECLARE local_now TIME;BEGIN
 UPDATE update_rollouts SET status='ACTIVE',started_at=COALESCE(started_at,now()),updated_at=now()WHERE status='SCHEDULED' AND scheduled_start_at<=now();
 RETURN QUERY SELECT r.id,rel.id,rel.channel,rel.version_code,rel.version_name,rel.package_name,rel.apk_size_bytes,rel.apk_sha256,rel.certificate_sha256,rel.release_notes
 FROM update_rollout_devices rd JOIN update_rollouts r ON r.id=rd.rollout_id JOIN player_releases rel ON rel.id=r.release_id JOIN devices d ON d.id=rd.device_id
 WHERE rd.device_id=p_device_id AND r.status='ACTIVE' AND rel.status='PUBLISHED' AND rel.channel=d.update_channel AND rel.version_code>p_current_version AND (p_release_id IS NULL OR rel.id=p_release_id)
 AND rd.cohort_score < r.waves[r.current_wave]*100 AND (r.maintenance_start_local IS NULL OR CASE WHEN r.maintenance_start_local<=r.maintenance_end_local THEN (now() AT TIME ZONE r.maintenance_timezone)::time>=r.maintenance_start_local AND (now() AT TIME ZONE r.maintenance_timezone)::time<r.maintenance_end_local ELSE (now() AT TIME ZONE r.maintenance_timezone)::time>=r.maintenance_start_local OR (now() AT TIME ZONE r.maintenance_timezone)::time<r.maintenance_end_local END)
 ORDER BY rel.version_code DESC LIMIT 1;
END$$;
REVOKE ALL ON FUNCTION public.get_eligible_player_release(UUID,BIGINT,UUID) FROM PUBLIC,anon,authenticated;GRANT EXECUTE ON FUNCTION public.get_eligible_player_release(UUID,BIGINT,UUID) TO service_role;

CREATE OR REPLACE FUNCTION public.reflect_update_attempt_in_rollout() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=public AS $$
DECLARE rid UUID;attempted INTEGER;failed INTEGER;r update_rollouts;
BEGIN
 SELECT rd.rollout_id INTO rid FROM update_rollout_devices rd JOIN update_rollouts ur ON ur.id=rd.rollout_id WHERE rd.device_id=NEW.device_id AND ur.release_id=NEW.release_id AND ur.status IN('ACTIVE','PAUSED','PAUSED_AUTO') LIMIT 1;
 IF rid IS NULL THEN RETURN NEW;END IF;
 UPDATE update_rollout_devices SET state=CASE WHEN NEW.state='INSTALLED' THEN 'INSTALLED' WHEN NEW.state IN('INSTALL_FAILED','UPDATE_RECOVERY_REQUIRED') THEN 'FAILED' WHEN NEW.state IN('INSTALLING','POST_UPDATE_VERIFYING','USER_ACTION_REQUIRED') THEN 'INSTALLING' ELSE state END,installed_at=CASE WHEN NEW.state='INSTALLED' THEN COALESCE(installed_at,now()) ELSE installed_at END,failure_code=CASE WHEN NEW.state IN('INSTALL_FAILED','UPDATE_RECOVERY_REQUIRED') THEN NEW.failure_code ELSE failure_code END WHERE rollout_id=rid AND device_id=NEW.device_id;
 SELECT count(*) FILTER(WHERE state IN('INSTALLING','INSTALLED','FAILED')),count(*) FILTER(WHERE state='FAILED') INTO attempted,failed FROM update_rollout_devices WHERE rollout_id=rid;
 SELECT * INTO r FROM update_rollouts WHERE id=rid FOR UPDATE;
 IF r.status='ACTIVE' AND attempted>=5 AND failed*100.0/attempted>=r.failure_threshold_percent THEN UPDATE update_rollouts SET status='PAUSED_AUTO',paused_at=now(),updated_at=now()WHERE id=rid;
 ELSIF r.status='ACTIVE' AND r.current_wave=array_length(r.waves,1) AND NOT EXISTS(SELECT 1 FROM update_rollout_devices WHERE rollout_id=rid AND state NOT IN('INSTALLED','FAILED','NO_DEVICE','CHANNEL_MISMATCH')) THEN UPDATE update_rollouts SET status='COMPLETED',completed_at=now(),updated_at=now()WHERE id=rid;END IF;
 RETURN NEW;END$$;
CREATE TRIGGER device_attempt_rollout_reflection AFTER INSERT OR UPDATE ON public.device_update_attempts FOR EACH ROW EXECUTE FUNCTION public.reflect_update_attempt_in_rollout();
