import { afterEach, describe, expect, it, vi } from "vitest";
import type { SupabaseClient } from "@supabase/supabase-js";
import { assignPlaylistVersion, createPlaylist, createScreen, enqueuePlayerCommand, listHealthEvents, listMediaAssets, listPlaylists, listPublishedPlaylistVersions, listRecentCommands, listScreens, login, pairPlayer, publishPlaylistDraft, savePlaylistDraft, uploadMediaAsset } from "./api";

describe("Admin API", () => {
  afterEach(() => vi.unstubAllGlobals());
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
    expect(select).toHaveBeenCalledWith(expect.stringContaining("runtime_status:device_runtime_status"));
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

  it("lists the authenticated media library", async () => {
    const order = vi.fn().mockResolvedValue({ data: [{ id: "a1" }], error: null });
    const select = vi.fn(() => ({ order }));
    const client = { from: vi.fn(() => ({ select })) } as unknown as SupabaseClient;
    await expect(listMediaAssets(client)).resolves.toEqual([{ id:"a1" }]);
    expect(client.from).toHaveBeenCalledWith("player_media_assets");
    expect(select).toHaveBeenCalledWith(expect.stringContaining("storage_path"));
  });

  it("deduplicates upload by the owner's SHA before Storage", async () => {
    const order = vi.fn().mockResolvedValue({ data: [{ id:"same", sha256:"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad" }], error:null });
    const upload = vi.fn();
    const client = { from: vi.fn(() => ({ select: () => ({ order }) })), storage:{ from:vi.fn(() => ({ upload })) } } as unknown as SupabaseClient;
    const result = await uploadMediaAsset(client,"user",new File(["abc"],"a.png",{type:"image/png"}));
    expect(result).toMatchObject({ deduplicated:true, asset:{ id:"same" } });
    expect(upload).not.toHaveBeenCalled();
  });

  it("uploads to a tenant namespace and registers through RPC", async () => {
    vi.stubGlobal("crypto", { randomUUID: () => "123e4567-e89b-42d3-a456-426614174000" });
    const order = vi.fn().mockResolvedValue({ data: [], error:null });
    const upload = vi.fn().mockResolvedValue({ data:{ path:"ok" }, error:null });
    const rpc = vi.fn().mockResolvedValue({ data:{ id:"asset" }, error:null });
    const states:string[]=[];
    const client = { from: vi.fn(() => ({ select: () => ({ order }) })), storage:{ from:vi.fn(() => ({ upload })) }, rpc } as unknown as SupabaseClient;
    await expect(uploadMediaAsset(client,"owner-1",new File(["abc"],"photo.png",{type:"image/png"}),state=>states.push(state))).resolves.toMatchObject({deduplicated:false});
    expect(upload).toHaveBeenCalledWith("users/owner-1/123e4567-e89b-42d3-a456-426614174000/original.png",expect.any(File),{contentType:"image/png",upsert:false});
    expect(rpc).toHaveBeenCalledWith("register_player_media_asset",expect.objectContaining({p_asset_id:"123e4567-e89b-42d3-a456-426614174000",p_expected_size_bytes:3,p_mime_type:"image/png"}));
    expect(states).toEqual(["Calculando integridade…","Enviando…","Registrando…","Concluído."]);
  });

  it("does not register a failed Storage upload", async () => {
    vi.stubGlobal("crypto", { randomUUID: () => "123e4567-e89b-42d3-a456-426614174000" });
    const order = vi.fn().mockResolvedValue({ data: [], error:null });
    const rpc = vi.fn();
    const client = { from:vi.fn(() => ({select:()=>({order})})), storage:{from:()=>({upload:vi.fn().mockResolvedValue({error:{}})})}, rpc } as unknown as SupabaseClient;
    await expect(uploadMediaAsset(client,"owner",new File(["abc"],"a.mp4",{type:"video/mp4"}))).rejects.toThrow("Falha ao enviar");
    expect(rpc).not.toHaveBeenCalled();
  });

  it("lists playlists with drafts and immutable history", async () => {
    const order = vi.fn().mockResolvedValue({data:[{id:"p1"}],error:null});
    const select = vi.fn(() => ({order}));
    const client={from:vi.fn(()=>({select}))} as unknown as SupabaseClient;
    await expect(listPlaylists(client)).resolves.toHaveLength(1);
    expect(select).toHaveBeenCalledWith(expect.stringContaining("player_playlist_drafts"));
    expect(select).toHaveBeenCalledWith(expect.stringContaining("player_playlist_versions"));
  });

  it("creates a trimmed owner-scoped playlist", async () => {
    const single=vi.fn().mockResolvedValue({data:{id:"p1",name:"Grade"},error:null});
    const select=vi.fn(()=>({single})); const insert=vi.fn(()=>({select}));
    const client={from:vi.fn(()=>({insert}))} as unknown as SupabaseClient;
    await createPlaylist(client,"owner"," Grade ");
    expect(insert).toHaveBeenCalledWith({owner_id:"owner",name:"Grade"});
  });

  it("saves drafts and publishes only through secure RPCs", async () => {
    const rpc=vi.fn().mockResolvedValue({data:{id:"v1"},error:null});
    const client={rpc} as unknown as SupabaseClient;
    const items=[{id:"i",order:0,kind:"MEDIA" as const,assetId:"a"}];
    await savePlaylistDraft(client,"p1",items);
    await publishPlaylistDraft(client,"p1");
    expect(rpc).toHaveBeenNthCalledWith(1,"save_player_playlist_draft",{p_playlist_id:"p1",p_items:items});
    expect(rpc).toHaveBeenNthCalledWith(2,"publish_player_playlist_draft",{p_playlist_id:"p1"});
  });

  it("enqueues each safe command through the owner-authorizing RPC",async()=>{for(const type of ["GET_STATUS","SYNC_NOW","RELOAD_PLAYLIST"] as const){const rpc=vi.fn().mockResolvedValue({data:{id:type,status:"PENDING"},error:null});const client={rpc} as unknown as SupabaseClient;await expect(enqueuePlayerCommand(client,"screen",type)).resolves.toMatchObject({status:"PENDING"});expect(rpc).toHaveBeenCalledWith("enqueue_player_command",{p_screen_id:"screen",p_command_type:type,p_payload:null});}});

  it("loads only recent command history visible through RLS",async()=>{const limit=vi.fn().mockResolvedValue({data:[{id:"c1"}],error:null});const order=vi.fn(()=>({limit}));const select=vi.fn(()=>({order}));const client={from:vi.fn(()=>({select}))} as unknown as SupabaseClient;await expect(listRecentCommands(client)).resolves.toHaveLength(1);expect(client.from).toHaveBeenCalledWith("device_commands");expect(limit).toHaveBeenCalledWith(50);});

  it("loads at most 50 recent tenant-scoped health events",async()=>{const limit=vi.fn().mockResolvedValue({data:[{id:"e1"}],error:null});const order=vi.fn(()=>({limit}));const client={from:vi.fn(()=>({select:vi.fn(()=>({order}))}))} as unknown as SupabaseClient;await expect(listHealthEvents(client)).resolves.toHaveLength(1);expect(client.from).toHaveBeenCalledWith("device_health_events");expect(limit).toHaveBeenCalledWith(50);});

  it("reports command enqueue and history failures safely",async()=>{const rpcClient={rpc:vi.fn().mockResolvedValue({data:null,error:{}})} as unknown as SupabaseClient;await expect(enqueuePlayerCommand(rpcClient,"screen","GET_STATUS")).rejects.toThrow("Não foi possível enviar");const order=vi.fn(()=>({limit:vi.fn().mockResolvedValue({data:null,error:{}})}));const listClient={from:vi.fn(()=>({select:vi.fn(()=>({order}))}))} as unknown as SupabaseClient;await expect(listRecentCommands(listClient)).rejects.toThrow("Não foi possível carregar");});
});
