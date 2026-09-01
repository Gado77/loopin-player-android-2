-- Device presence is server-authored by the device-pairing Edge Function using service_role.
-- Dashboard users retain SELECT through the existing ownership RLS policies, but cannot
-- forge last_seen_at, app_version or metadata with a direct table update.
DROP POLICY IF EXISTS devices_update_owned ON public.devices;
REVOKE UPDATE ON TABLE public.devices FROM anon, authenticated;
