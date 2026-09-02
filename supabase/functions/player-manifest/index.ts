import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const jsonHeaders = { "Content-Type": "application/json", "Cache-Control": "no-store" };
const reply = (status: number, body?: unknown, headers: Record<string, string> = {}) =>
  new Response(body === undefined ? null : JSON.stringify(body), { status, headers: { ...jsonHeaders, ...headers } });
const sha256 = async (value: string) => Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value))))
  .map((byte) => byte.toString(16).padStart(2, "0")).join("");

Deno.serve(async (request) => {
  if (request.method !== "GET") return reply(405, { error: "method_not_allowed" }, { Allow: "GET" });
  const authorization = request.headers.get("Authorization") ?? "";
  const credential = authorization.startsWith("Bearer ") ? authorization.slice(7).trim() : "";
  if (!credential) return reply(401, { error: "invalid_device_credential" });
  try {
    const client = createClient(
      Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
      { auth: { persistSession: false, autoRefreshToken: false } },
    );
    const credentialHash = await sha256(credential);
    const { data: auth, error: authError } = await client.from("device_credentials")
      .select("device_id, devices!inner(screen_id, pairing_status)")
      .eq("credential_hash", credentialHash).is("revoked_at", null).maybeSingle();
    const device = auth?.devices as unknown as { screen_id: string | null; pairing_status: string } | null;
    if (authError || !auth || !device || device.pairing_status !== "PAIRED" || !device.screen_id) {
      return reply(401, { error: "invalid_device_credential" });
    }
    const { data: assignment, error } = await client.from("screen_playlist_assignments")
      .select("player_playlist_versions!inner(version_number, manifest, manifest_sha256)")
      .eq("screen_id", device.screen_id).maybeSingle();
    if (error) return reply(409, { error: "invalid_manifest_assignment" });
    if (!assignment) return new Response(null, { status: 204, headers: { "Cache-Control": "no-store" } });
    const versionRow = assignment.player_playlist_versions as unknown as {
      version_number: number; manifest: Record<string, unknown>; manifest_sha256: string;
    };
    if (versionRow.manifest?.schemaVersion !== 2 || !Array.isArray(versionRow.manifest?.items)) {
      return reply(409, { error: "invalid_manifest_snapshot" });
    }
    const etag = `"${versionRow.manifest_sha256}"`;
    const ifNoneMatch = request.headers.get("If-None-Match")?.trim().replace(/^W\//, "");
    if (ifNoneMatch === etag) {
      return new Response(null, { status: 304, headers: { ETag: etag, "X-Loopin-Playlist-Version": String(versionRow.version_number) } });
    }
    return reply(200, versionRow.manifest, {
      ETag: etag,
      "X-Loopin-Playlist-Version": String(versionRow.version_number),
    });
  } catch {
    return reply(500, { error: "internal_error" });
  }
});
