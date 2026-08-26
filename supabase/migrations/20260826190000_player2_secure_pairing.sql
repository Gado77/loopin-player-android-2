CREATE EXTENSION IF NOT EXISTS pgcrypto;

ALTER TABLE public.device_pairing_sessions
    ADD COLUMN IF NOT EXISTS credential_hash TEXT;

ALTER TABLE public.device_pairing_sessions
    DROP CONSTRAINT IF EXISTS device_pairing_sessions_credential_hash_check;
ALTER TABLE public.device_pairing_sessions
    ADD CONSTRAINT device_pairing_sessions_credential_hash_check
    CHECK (credential_hash ~ '^[a-f0-9]{64}$');
ALTER TABLE public.device_pairing_sessions
    ALTER COLUMN credential_hash SET NOT NULL;

CREATE TABLE IF NOT EXISTS public.device_pairing_rate_limits (
    key_hash TEXT PRIMARY KEY CHECK (key_hash ~ '^[a-f0-9]{64}$'),
    window_started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0)
);

ALTER TABLE public.device_pairing_rate_limits ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.device_pairing_rate_limits FROM anon, authenticated;

CREATE OR REPLACE FUNCTION public.take_device_pairing_rate_limit(
    p_key_hash TEXT,
    p_limit INTEGER,
    p_window_seconds INTEGER
) RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    allowed BOOLEAN;
BEGIN
    IF p_key_hash !~ '^[a-f0-9]{64}$' OR p_limit < 1 OR p_window_seconds < 1 THEN
        RETURN FALSE;
    END IF;

    INSERT INTO public.device_pairing_rate_limits(key_hash, window_started_at, attempts)
    VALUES (p_key_hash, now(), 1)
    ON CONFLICT (key_hash) DO UPDATE SET
        window_started_at = CASE
            WHEN device_pairing_rate_limits.window_started_at <= now() - make_interval(secs => p_window_seconds)
            THEN now()
            ELSE device_pairing_rate_limits.window_started_at
        END,
        attempts = CASE
            WHEN device_pairing_rate_limits.window_started_at <= now() - make_interval(secs => p_window_seconds)
            THEN 1
            ELSE device_pairing_rate_limits.attempts + 1
        END
    RETURNING attempts <= p_limit INTO allowed;

    RETURN allowed;
END;
$$;

CREATE OR REPLACE FUNCTION public.confirm_device_pairing(
    p_session_id UUID,
    p_user_id UUID,
    p_screen_id UUID DEFAULT NULL,
    p_screen_name TEXT DEFAULT NULL
) RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    pairing public.device_pairing_sessions%ROWTYPE;
    device public.devices%ROWTYPE;
    selected_screen public.screens%ROWTYPE;
    new_credential_id UUID;
BEGIN
    IF p_user_id IS NULL THEN
        RAISE EXCEPTION 'authentication required';
    END IF;

    SELECT * INTO pairing
      FROM public.device_pairing_sessions
     WHERE id = p_session_id
     FOR UPDATE;

    IF NOT FOUND THEN RAISE EXCEPTION 'pairing session not found'; END IF;
    IF pairing.consumed_at IS NOT NULL THEN RAISE EXCEPTION 'pairing session already consumed'; END IF;
    IF pairing.expires_at <= now() THEN RAISE EXCEPTION 'pairing session expired'; END IF;

    SELECT * INTO device
      FROM public.devices
     WHERE id = pairing.device_id
     FOR UPDATE;

    IF NOT FOUND THEN RAISE EXCEPTION 'device not found'; END IF;
    IF device.pairing_status = 'PAIRED' OR device.screen_id IS NOT NULL THEN
        RAISE EXCEPTION 'device already paired';
    END IF;

    IF p_screen_id IS NOT NULL THEN
        SELECT * INTO selected_screen
          FROM public.screens
         WHERE id = p_screen_id
           AND owner_id = p_user_id
           AND status = 'ACTIVE'
         FOR UPDATE;
        IF NOT FOUND THEN RAISE EXCEPTION 'screen unavailable'; END IF;
    ELSE
        IF p_screen_name IS NULL OR length(trim(p_screen_name)) NOT BETWEEN 1 AND 100 THEN
            RAISE EXCEPTION 'invalid screen name';
        END IF;
        INSERT INTO public.screens(owner_id, name, status)
        VALUES (p_user_id, trim(p_screen_name), 'ACTIVE')
        RETURNING * INTO selected_screen;
    END IF;

    INSERT INTO public.device_credentials(device_id, credential_hash)
    VALUES (device.id, pairing.credential_hash)
    RETURNING id INTO new_credential_id;

    UPDATE public.devices
       SET screen_id = selected_screen.id,
           pairing_status = 'PAIRED',
           paired_at = now(),
           updated_at = now()
     WHERE id = device.id;

    UPDATE public.device_pairing_sessions
       SET consumed_at = now(),
           confirmed_by = p_user_id,
           confirmed_screen_id = selected_screen.id
     WHERE id = pairing.id;

    UPDATE public.device_pairing_sessions
       SET expires_at = LEAST(expires_at, now())
     WHERE device_id = device.id
       AND id <> pairing.id
       AND consumed_at IS NULL;

    INSERT INTO public.device_events(device_id, event_type, payload)
    VALUES (
        device.id,
        'DEVICE_PAIRED',
        jsonb_build_object(
            'screen_id', selected_screen.id,
            'confirmed_by', p_user_id,
            'credential_id', new_credential_id
        )
    );

    RETURN jsonb_build_object(
        'device_id', device.id,
        'screen_id', selected_screen.id,
        'screen_name', selected_screen.name,
        'paired_at', now()
    );
END;
$$;

REVOKE ALL ON FUNCTION public.take_device_pairing_rate_limit(TEXT, INTEGER, INTEGER) FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.confirm_device_pairing(UUID, UUID, UUID, TEXT) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.take_device_pairing_rate_limit(TEXT, INTEGER, INTEGER) TO service_role;
GRANT EXECUTE ON FUNCTION public.confirm_device_pairing(UUID, UUID, UUID, TEXT) TO service_role;

