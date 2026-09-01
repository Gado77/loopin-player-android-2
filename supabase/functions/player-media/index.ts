import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const headers = { "Content-Type": "application/json", "Cache-Control": "no-store" };
const reply = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers });
const sha256 = async (value: string) => Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value))))
  .map((byte) => byte.toString(16).padStart(2, "0")).join("");
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

Deno.serve(async (request) => {
  if (request.method !== "POST") return reply(405, { error: "method_not_allowed" });
  const authorization = request.headers.get("Authorization") ?? "";
  const credential = authorization.startsWith("Bearer ") ? authorization.slice(7).trim() : "";
  if (!credential) return reply(401, { error: "invalid_device_credential" });
  let assetId = "";
  try { assetId = String((await request.json()).asset_id ?? "").trim(); } catch { return reply(400, { error: "invalid_request" }); }
  if (!UUID.test(assetId)) return reply(400, { error: "invalid_asset_id" });
  try {
    const client = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
      { auth: { persistSession: false, autoRefreshToken: false } });
    const { data: auth, error: authError } = await client.from("device_credentials")
      .select("devices!inner(screen_id, pairing_status)").eq("credential_hash", await sha256(credential))
      .is("revoked_at", null).maybeSingle();
    const device = auth?.devices as unknown as { screen_id: string | null; pairing_status: string } | null;
    if (authError || !auth || !device?.screen_id || device.pairing_status !== "PAIRED") {
      return reply(401, { error: "invalid_device_credential" });
    }
    const { data: assignment, error: assignmentError } = await client.from("screen_playlist_assignments")
      .select("player_playlist_versions!inner(manifest)").eq("screen_id", device.screen_id).maybeSingle();
    const version = assignment?.player_playlist_versions as unknown as { manifest?: { items?: Array<Record<string, unknown>> } } | null;
    if (assignmentError || !version?.manifest?.items) return reply(403, { error: "asset_not_authorized" });
    const authorized = version.manifest.items.some((item) => item.kind === "MEDIA" && item.assetId === assetId);
    if (!authorized) return reply(403, { error: "asset_not_authorized" });
    const { data: asset, error: assetError } = await client.from("player_media_assets")
      .select("storage_path").eq("id", assetId).maybeSingle();
    if (assetError || !asset?.storage_path) return reply(404, { error: "asset_not_found" });
    const { data: signed, error: signError } = await client.storage.from("player2-media")
      .createSignedUrl(asset.storage_path, 900);
    if (signError || !signed?.signedUrl) return reply(503, { error: "media_temporarily_unavailable" });
    return reply(200, { download_url: signed.signedUrl, expires_in: 900 });
  } catch { return reply(500, { error: "internal_error" }); }
});
