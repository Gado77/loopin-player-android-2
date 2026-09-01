import { describe, expect, it, vi } from "vitest";
import type { SupabaseClient } from "@supabase/supabase-js";
import { assignPlaylistVersion, createScreen, listPublishedPlaylistVersions, listScreens, login, pairPlayer } from "./api";

describe("Admin API", () => {
  it("faz login por email e senha", async () => {
    const signInWithPassword = vi.fn().mockResolvedValue({ data: { session: { access_token: "token" } }, error: null });
    const client = { auth: { signInWithPassword } } as unknown as SupabaseClient;
    await expect(login(client, "user@example.com", "secret123")).resolves.toMatchObject({ access_token: "token" });
    expect(signInWithPassword).toHaveBeenCalledWith({ email: "user@example.com", password: "secret123" });
  });

  it("retorna erro amigável no login", async () => {
    const client = { auth: { signInWithPassword: vi.fn().mockResolvedValue({ data: {}, error: {} }) } } as unknown as SupabaseClient;
    await expect(login(client, "x@y.com", "bad")).rejects.toThrow("Email ou senha inválidos");
  });

  it("lista telas respeitando consulta autenticada", async () => {
    const order = vi.fn().mockResolvedValue({ data: [{ id: "s1", name: "Loja" }], error: null });
    const select = vi.fn(() => ({ order }));
    const client = { from: vi.fn(() => ({ select })) } as unknown as SupabaseClient;
    await expect(listScreens(client)).resolves.toHaveLength(1);
    expect(select).toHaveBeenCalledWith(expect.stringContaining("last_seen_at"));
    expect(select).toHaveBeenCalledWith(expect.stringContaining("app_version"));
    expect(select).toHaveBeenCalledWith(expect.stringContaining("playlist_assignment:screen_playlist_assignments"));
  });

  it("lista somente versões publicadas visíveis por RLS", async () => {
    const order = vi.fn().mockResolvedValue({ data: [{ id: "v1", version_number: 1 }], error: null });
    const select = vi.fn(() => ({ order }));
    const client = { from: vi.fn(() => ({ select })) } as unknown as SupabaseClient;
    await expect(listPublishedPlaylistVersions(client)).resolves.toHaveLength(1);
    expect(client.from).toHaveBeenCalledWith("player_playlist_versions");
  });

  it("altera associação por RPC sem escrita direta", async () => {
    const rpc = vi.fn().mockResolvedValue({ data: { screen_id: "s1" }, error: null });
    const client = { rpc } as unknown as SupabaseClient;
    await assignPlaylistVersion(client, "s1", "v1");
    expect(rpc).toHaveBeenCalledWith("assign_player_playlist_version", {
      p_screen_id: "s1", p_playlist_version_id: "v1",
    });
  });

  it("cria tela para o usuário autenticado", async () => {
    const single = vi.fn().mockResolvedValue({ data: { id: "s1", owner_id: "u1", name: "Recepção", status: "ACTIVE" }, error: null });
    const select = vi.fn(() => ({ single }));
    const insert = vi.fn(() => ({ select }));
    const client = { from: vi.fn(() => ({ insert })) } as unknown as SupabaseClient;
    await expect(createScreen(client, "u1", " Recepção ")).resolves.toMatchObject({ name: "Recepção" });
    expect(insert).toHaveBeenCalledWith({ owner_id: "u1", name: "Recepção", status: "ACTIVE" });
  });

  it("confirma código usando a Edge Function", async () => {
    const invoke = vi.fn().mockResolvedValue({ data: { state: "PAIRED", device_id: "d1" }, error: null });
    const client = { functions: { invoke } } as unknown as SupabaseClient;
    await pairPlayer(client, "s1", { kind: "code", code: "582731" });
    expect(invoke).toHaveBeenCalledWith("device-pairing", { body: { action: "confirm", screen_id: "s1", pairing_code: "582731" } });
  });

  it("confirma QR sem persistir token", async () => {
    const invoke = vi.fn().mockResolvedValue({ data: { state: "PAIRED", device_id: "d1" }, error: null });
    const client = { functions: { invoke } } as unknown as SupabaseClient;
    await pairPlayer(client, "s1", { kind: "token", token: "A".repeat(43) });
    expect(invoke).toHaveBeenCalledWith("device-pairing", { body: { action: "confirm", screen_id: "s1", pairing_token: "A".repeat(43) } });
  });
});
