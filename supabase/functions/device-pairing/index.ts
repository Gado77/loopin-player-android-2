import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2.105.3";

const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
const anonKey = Deno.env.get("SUPABASE_ANON_KEY") ?? "";
const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";

if (!supabaseUrl || !anonKey || !serviceRoleKey) {
  throw new Error("Supabase environment is not configured");
}

const service = createClient(supabaseUrl, serviceRoleKey, {
  auth: { autoRefreshToken: false, persistSession: false },
});
const encoder = new TextEncoder();
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const HEX_64 = /^[a-f0-9]{64}$/;

const headers = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Cache-Control": "no-store",
  "Content-Type": "application/json",
};

function json(status: number, body: Record<string, unknown>): Response {
  return new Response(JSON.stringify(body), { status, headers });
}

async function requestBody(req: Request): Promise<Record<string, unknown> | null> {
  if (Number(req.headers.get("content-length") ?? "0") > 16_384) return null;
  try {
    const value = await req.json();
    return value && typeof value === "object" && !Array.isArray(value)
      ? value as Record<string, unknown>
      : null;
  } catch {
    return null;
  }
}

function randomToken(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  return btoa(String.fromCharCode(...bytes))
    .replaceAll("+", "-")
    .replaceAll("/", "_")
    .replaceAll("=", "");
}

function randomCode(): string {
  return (crypto.getRandomValues(new Uint32Array(1))[0] % 1_000_000)
    .toString()
    .padStart(6, "0");
}

async function sha256(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", encoder.encode(value));
  return [...new Uint8Array(digest)]
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}

function bearer(req: Request): string | null {
  return req.headers.get("authorization")?.match(/^Bearer\s+(.+)$/i)?.[1] ?? null;
}

function heartbeatMetadata(value: unknown): Record<string, unknown> | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const source = value as Record<string, unknown>;
  const result: Record<string, unknown> = {};
  for (const key of ["connection", "playback_state", "cache_state", "health_state"] as const) {
    if (typeof source[key] === "string" && source[key].length <= 32) result[key] = source[key];
  }
  if (typeof source.free_storage_bytes === "number" && Number.isSafeInteger(source.free_storage_bytes) && source.free_storage_bytes >= 0) {
    result.free_storage_bytes = source.free_storage_bytes;
  }
  if (source.last_sync_epoch_ms === null) result.last_sync_epoch_ms = null;
  else if (typeof source.last_sync_epoch_ms === "number" && Number.isSafeInteger(source.last_sync_epoch_ms) && source.last_sync_epoch_ms >= 0) {
    result.last_sync_epoch_ms = source.last_sync_epoch_ms;
  }
  return result;
}

async function authenticatedUser(req: Request) {
  const token = bearer(req);
  if (!token) return null;
  const client = createClient(supabaseUrl, anonKey, {
    auth: { autoRefreshToken: false, persistSession: false },
    global: { headers: { Authorization: `Bearer ${token}` } },
  });
  const { data, error } = await client.auth.getUser(token);
  return error ? null : data.user;
}

async function rateLimit(req: Request, scope: string, limit: number, seconds: number) {
  const source = req.headers.get("x-forwarded-for")?.split(",")[0]?.trim() ?? "unknown";
  const { data, error } = await service.rpc("take_device_pairing_rate_limit", {
    p_key_hash: await sha256(`${scope}:${source}`),
    p_limit: limit,
    p_window_seconds: seconds,
  });
  return !error && data === true;
}

async function startPairing(req: Request, body: Record<string, unknown>): Promise<Response> {
  const internalId = String(body.internal_id ?? "").trim().toLowerCase();
  const friendlyCode = String(body.friendly_code ?? "").trim();
  const credentialHash = String(body.credential_hash ?? "").trim().toLowerCase();
  if (!UUID.test(internalId) || !/^\d{6}$/.test(friendlyCode) || !HEX_64.test(credentialHash)) {
    return json(400, { error: "invalid_device_proof" });
  }
  if (!(await rateLimit(req, `start:${internalId}`, 8, 300))) {
    return json(429, { error: "rate_limited" });
  }

  let { data: device, error: lookupError } = await service
    .from("devices")
    .select("id, pairing_status, screen_id")
    .eq("internal_id", internalId)
    .maybeSingle();
  if (lookupError) return json(500, { error: "device_lookup_failed" });

  if (!device) {
    const { data: created, error } = await service
      .from("devices")
      .insert({ internal_id: internalId, friendly_code: friendlyCode, pairing_status: "UNPAIRED" })
      .select("id, pairing_status, screen_id")
      .single();
    if (error || !created) return json(500, { error: "device_creation_failed" });
    device = created;
  }
  if (device.pairing_status === "PAIRED" || device.screen_id) {
    return json(409, { error: "device_already_paired" });
  }

  await service
    .from("devices")
    .update({ friendly_code: friendlyCode, updated_at: new Date().toISOString() })
    .eq("id", device.id);
  await service.rpc("cleanup_expired_pairing_sessions");
  await service
    .from("device_pairing_sessions")
    .delete()
    .eq("device_id", device.id)
    .is("consumed_at", null);

  for (let attempt = 0; attempt < 10; attempt++) {
    const token = randomToken();
    const code = randomCode();
    const expiresAt = new Date(Date.now() + 30_000).toISOString();
    const { error } = await service.from("device_pairing_sessions").insert({
      device_id: device.id,
      code,
      token_hash: await sha256(token),
      credential_hash: credentialHash,
      expires_at: expiresAt,
    });
    if (!error) {
      return json(201, {
        state: "UNPAIRED",
        pairing_token: token,
        pairing_code: code,
        qr_payload: `loopin://pair?v=1&type=loopin-device-pairing&token=${encodeURIComponent(token)}`,
        expires_at: expiresAt,
        expires_in: 30,
      });
    }
  }
  return json(503, { error: "pairing_session_unavailable" });
}

async function pairingStatus(body: Record<string, unknown>): Promise<Response> {
  const token = String(body.pairing_token ?? "").trim();
  if (token.length < 32 || token.length > 128) return json(400, { error: "invalid_token" });

  const { data: session, error } = await service
    .from("device_pairing_sessions")
    .select("device_id, expires_at, consumed_at, confirmed_screen_id")
    .eq("token_hash", await sha256(token))
    .maybeSingle();
  if (error) return json(500, { error: "pairing_lookup_failed" });
  if (!session) return json(404, { state: "INVALID" });

  if (session.consumed_at && session.confirmed_screen_id) {
    const { data: device } = await service
      .from("devices")
      .select("id, screen_id, screens(name)")
      .eq("id", session.device_id)
      .maybeSingle();
    const relation = device?.screens;
    const screen = Array.isArray(relation) ? relation[0] : relation;
    return json(200, {
      state: "PAIRED",
      device_id: device?.id ?? session.device_id,
      screen_id: device?.screen_id ?? session.confirmed_screen_id,
      screen_name: screen?.name ?? null,
    });
  }
  if (new Date(session.expires_at).getTime() <= Date.now()) {
    return json(410, { state: "EXPIRED" });
  }
  return json(200, { state: "UNPAIRED", expires_at: session.expires_at });
}

async function confirmPairing(req: Request, body: Record<string, unknown>): Promise<Response> {
  const user = await authenticatedUser(req);
  if (!user) return json(401, { error: "authentication_required" });
  if (!(await rateLimit(req, `confirm:${user.id}`, 12, 300))) {
    return json(429, { error: "rate_limited" });
  }

  const token = String(body.pairing_token ?? body.token ?? "").trim();
  const code = String(body.pairing_code ?? body.code ?? "").trim();
  const screenId = String(body.screen_id ?? "").trim() || null;
  const screenName = String(body.screen_name ?? "").trim() || null;
  if (!token && !/^\d{6}$/.test(code)) return json(400, { error: "invalid_pairing_proof" });
  if (!screenId && (!screenName || screenName.length > 100)) {
    return json(400, { error: "invalid_screen" });
  }

  let query = service
    .from("device_pairing_sessions")
    .select("id, expires_at, consumed_at");
  query = token ? query.eq("token_hash", await sha256(token)) : query.eq("code", code);
  const { data: matches, error } = await query.order("created_at", { ascending: false }).limit(2);
  if (error) return json(500, { error: "pairing_lookup_failed" });
  const session = matches?.find(
    (candidate) => !candidate.consumed_at && new Date(candidate.expires_at).getTime() > Date.now(),
  );
  if (!session) return json(410, { error: "pairing_expired_or_consumed" });

  const { data, error: confirmationError } = await service.rpc("confirm_device_pairing", {
    p_session_id: session.id,
    p_user_id: user.id,
    p_screen_id: screenId,
    p_screen_name: screenName,
  });
  if (confirmationError) {
    if (/screen unavailable/i.test(confirmationError.message)) {
      return json(403, { error: "forbidden_screen" });
    }
    if (/device already paired/i.test(confirmationError.message)) {
      return json(409, { error: "device_already_paired" });
    }
    if (/expired|consumed/i.test(confirmationError.message)) {
      return json(409, { error: "pairing_expired_or_consumed" });
    }
    return json(500, { error: "pairing_failed" });
  }
  return json(200, { state: "PAIRED", ...data });
}

async function heartbeat(req: Request, body: Record<string, unknown>): Promise<Response> {
  const credential = bearer(req);
  if (!credential || credential.length < 40 || credential.length > 128) {
    return json(401, { error: "invalid_device_credential" });
  }
  const { data: stored, error } = await service
    .from("device_credentials")
    .select("device_id")
    .eq("credential_hash", await sha256(credential))
    .is("revoked_at", null)
    .maybeSingle();
  if (error || !stored) return json(401, { error: "invalid_device_credential" });

  const now = new Date().toISOString();
  const update: Record<string, unknown> = { last_seen_at: now, updated_at: now };
  if (typeof body.app_version === "string") update.app_version = body.app_version.slice(0, 100);
  const metadata = heartbeatMetadata(body.metadata);
  if (metadata) update.metadata = metadata;
  const { error: updateError } = await service.from("devices").update(update).eq("id", stored.device_id);
  if (updateError) return json(500, { error: "heartbeat_failed" });
  return json(200, { ok: true, server_time: now });
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers });
  if (req.method !== "POST") return json(405, { error: "method_not_allowed" });

  const body = await requestBody(req);
  if (!body) return json(400, { error: "invalid_or_oversized_json" });
  const pathAction = new URL(req.url).pathname.split("/").filter(Boolean).pop() ?? "";
  const action = pathAction === "device-pairing" ? String(body.action ?? "") : pathAction;

  try {
    if (action === "create" || action === "start") return await startPairing(req, body);
    if (action === "status") return await pairingStatus(body);
    if (action === "confirm") return await confirmPairing(req, body);
    if (action === "heartbeat") return await heartbeat(req, body);
    return json(400, { error: "invalid_action" });
  } catch (error) {
    console.error("device-pairing unexpected error", error);
    return json(500, { error: "internal_error" });
  }
});
