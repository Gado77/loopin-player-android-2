import type { SupabaseClient } from "@supabase/supabase-js";
import type { PairingProof, PairingResult, PlaylistVersion, Screen } from "./types";
import { pairingErrorMessage } from "./pairing";

export async function login(client: SupabaseClient, email: string, password: string) {
  const { data, error } = await client.auth.signInWithPassword({ email, password });
  if (error) throw new Error("Email ou senha inválidos.");
  return data.session;
}

export async function logout(client: SupabaseClient) {
  const { error } = await client.auth.signOut();
  if (error) throw new Error("Não foi possível sair agora.");
}

export async function listScreens(client: SupabaseClient): Promise<Screen[]> {
  const { data, error } = await client
    .from("screens")
    .select("id, owner_id, name, status, created_at, devices(id, pairing_status, last_seen_at, app_version, metadata), screen_playlist_assignments(playlist_version_id, assigned_at, player_playlist_versions(id, playlist_id, version_number, manifest_sha256, published_at, player_playlists(id, name)))")
    .order("created_at", { ascending: false });
  if (error) throw new Error("Não foi possível carregar suas telas.");
  return (data ?? []) as unknown as Screen[];
}

export async function listPublishedPlaylistVersions(client: SupabaseClient): Promise<PlaylistVersion[]> {
  const { data, error } = await client.from("player_playlist_versions")
    .select("id, playlist_id, version_number, manifest_sha256, published_at, player_playlists(id, name)")
    .order("published_at", { ascending: false });
  if (error) throw new Error("Não foi possível carregar as playlists publicadas.");
  return (data ?? []) as unknown as PlaylistVersion[];
}

export async function assignPlaylistVersion(client: SupabaseClient, screenId: string, versionId: string) {
  const { data, error } = await client.rpc("assign_player_playlist_version", {
    p_screen_id: screenId, p_playlist_version_id: versionId,
  });
  if (error) throw new Error("Não foi possível alterar a playlist desta tela.");
  return data;
}

export async function createScreen(client: SupabaseClient, ownerId: string, name: string): Promise<Screen> {
  const normalized = name.trim();
  if (!normalized || normalized.length > 100) throw new Error("Informe um nome de tela válido.");
  const { data, error } = await client
    .from("screens")
    .insert({ owner_id: ownerId, name: normalized, status: "ACTIVE" })
    .select("id, owner_id, name, status, created_at")
    .single();
  if (error || !data) throw new Error("Não foi possível criar a tela.");
  return { ...data, devices: [] } as Screen;
}

export async function pairPlayer(
  client: SupabaseClient,
  screenId: string,
  proof: PairingProof,
): Promise<PairingResult> {
  const payload = proof.kind === "code"
    ? { action: "confirm", screen_id: screenId, pairing_code: proof.code }
    : { action: "confirm", screen_id: screenId, pairing_token: proof.token };
  const { data, error } = await client.functions.invoke("device-pairing", { body: payload });
  if (error) {
    let code = "network_error";
    const context = (error as { context?: Response }).context;
    if (context) {
      try {
        const response = await context.clone().json() as { error?: string };
        code = response.error ?? code;
      } catch { /* resposta sem JSON */ }
    }
    throw new Error(pairingErrorMessage(code));
  }
  return data as PairingResult;
}
