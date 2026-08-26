ALTER FUNCTION public.set_updated_at()
    SET search_path = public;

REVOKE ALL ON FUNCTION public.cleanup_expired_pairing_sessions()
    FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.cleanup_expired_pairing_sessions()
    TO service_role;

REVOKE ALL ON FUNCTION public.user_owns_screen(UUID)
    FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.user_owns_screen(UUID)
    TO service_role;
