import "./styles.css";
import type { Session } from "@supabase/supabase-js";
import { assignPlaylistVersion, createPlaylist, createScreen, enqueuePlayerCommand, listHealthEvents, listMediaAssets, listPlaylists, listPublishedPlaylistVersions, listRecentCommands, listScreens, login, logout, pairPlayer, publishPlaylistDraft, savePlaylistDraft, uploadMediaAsset } from "./api";
import { parsePairingCode, parsePairingQr } from "./pairing";
import { PairingScanner } from "./scanner";
import { classifyPresence, formatLastCommunication, presenceLabel } from "./presence";
import { DashboardPresenceRefresher } from "./refresh";
import { bindScreenGridEvents } from "./screen-grid-events";
import { supabase } from "./supabase";
import type { DeviceCommand, DeviceHealthEvent, DraftItem, MediaAsset, PairingProof, PlayerCommandType, Playlist, PlaylistVersion, Screen, ScreenDevice } from "./types";
import { mediaItem, moveItem, normalizeItems, removeItem, weatherItem } from "./playlist-editor";
import { parseAdminArea, type AdminArea } from "./navigation";
import { COMMAND_LABELS, commandResultLabel, commandStatusLabel, hasPendingCommands } from "./commands";
import { CommandRefreshController } from "./command-refresh";
import { diagnosticHealth, eventLabel, formatBytes, formatUptime, healthLabel, isStorageLow, recentEvents } from "./diagnostics";

const root = document.querySelector<HTMLDivElement>("#app")!;
const scanner = new PairingScanner();
let session: Session | null = null;
let screens: Screen[] = [];
let selectedScreen: Screen | null = null;
let proof: PairingProof | null = null;
let playlistVersions: PlaylistVersion[] = [];
let mediaAssets: MediaAsset[] = [];
let playlists: Playlist[] = [];
let activeArea: AdminArea = "screens";
let editingPlaylist: Playlist | null = null;
let draftItems: DraftItem[] = [];
let deviceCommands: DeviceCommand[] = [];
let healthEvents:DeviceHealthEvent[]=[];
const presenceRefresh = new DashboardPresenceRefresher(
  refreshDashboardData,
  () => document.visibilityState === "visible",
);
const commandRefresh = new CommandRefreshController(refreshCommandData,()=>document.visibilityState==="visible");

function escape(value: string) {
  return value.replace(/[&<>'"]/g, (character) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", "\"": "&quot;",
  })[character]!);
}

function notice(message: string, kind: "error" | "success" = "error") {
  const element = document.querySelector<HTMLDivElement>("#notice");
  if (!element) return;
  element.textContent = message;
  element.className = `notice ${kind}`;
}

function renderLogin() {
  scanner.stop();
  presenceRefresh.stop();
  commandRefresh.stop();
  root.innerHTML = `
    <main class="auth-shell">
      <section class="auth-card">
        <div class="brand">LOOPIN</div>
        <h1>Admin 2.0</h1>
        <p>Entre para gerenciar e vincular seus Players.</p>
        <form id="login-form">
          <label>Email<input name="email" type="email" autocomplete="email" required /></label>
          <label>Senha<input name="password" type="password" autocomplete="current-password" required /></label>
          <button type="submit">Entrar</button>
        </form>
        <div id="notice" class="notice" aria-live="polite"></div>
      </section>
    </main>`;
  document.querySelector<HTMLFormElement>("#login-form")!.addEventListener("submit", async (event) => {
    event.preventDefault();
    const formElement = event.currentTarget as HTMLFormElement;
    const form = new FormData(formElement);
    const button = formElement.querySelector<HTMLButtonElement>("button")!;
    button.disabled = true;
    try {
      session = await login(supabase, String(form.get("email")), String(form.get("password")));
      await loadDashboard();
    } catch (error) {
      notice((error as Error).message);
      button.disabled = false;
    }
  });
}

async function loadDashboard() {
  try {
    [screens, playlistVersions, mediaAssets, playlists, deviceCommands,healthEvents] = await Promise.all([listScreens(supabase), listPublishedPlaylistVersions(supabase), listMediaAssets(supabase), listPlaylists(supabase), listRecentCommands(supabase),listHealthEvents(supabase)]);
    renderDashboard();
  } catch (error) {
    renderDashboard();
    notice((error as Error).message);
  }
  presenceRefresh.start();
  if(hasPendingCommands(deviceCommands))commandRefresh.start();else commandRefresh.stop();
}

async function refreshDashboardData() {
  if (!session) return;
  [screens,healthEvents] = await Promise.all([listScreens(supabase),listHealthEvents(supabase)]);
  const grid = document.querySelector<HTMLElement>(".screen-grid");
  if (grid) grid.innerHTML = renderScreenCards();
}

async function refreshCommandData(){if(!session)return false;deviceCommands=await listRecentCommands(supabase);document.querySelectorAll<HTMLElement>(".command-history").forEach(element=>{element.innerHTML=renderCommandHistory(element.dataset.screenId!);});return hasPendingCommands(deviceCommands);}

function renderCommandHistory(screenId:string){return deviceCommands.filter(command=>command.screen_id===screenId).slice(0,5).map(command=>`<div class="command-row"><strong>${escape(command.command_type)}</strong><span class="command-${command.status.toLowerCase()}">${escape(commandStatusLabel(command.status))}</span><small>${escape(commandResultLabel(command))}${commandResultLabel(command)?" · ":""}${new Date(command.completed_at??command.created_at).toLocaleTimeString("pt-BR",{hour:"2-digit",minute:"2-digit"})}</small></div>`).join("")||`<small class="muted">Nenhum comando recente.</small>`;}

function renderScreenCards() {
  return screens.map((screen) => {
    const device = screen.devices?.find((item) => item.pairing_status === "PAIRED") ?? screen.devices?.[0];
    const paired = device?.pairing_status === "PAIRED";
    const runtime=device?.runtime_status;
    const health=diagnosticHealth(runtime);
    const presence = classifyPresence(device);
    const version = device?.app_version
      ? `<span>Player ${escape(device.app_version)}</span>`
      : "";
    const lastCommunication = device
      ? `<span>Última comunicação: ${escape(formatLastCommunication(device.last_seen_at))}</span>`
      : "";
    const assigned = screen.playlist_assignment?.player_playlist_versions;
    const placeholder = assigned ? "" : `<option value="" selected disabled>${playlistVersions.length ? "Selecione uma versão" : "Nenhuma versão publicada"}</option>`;
    const options = placeholder + playlistVersions.map((version) => `<option value="${version.id}" ${assigned?.id === version.id ? "selected" : ""}>${escape(version.player_playlists?.name ?? "Playlist")} · v${version.version_number}</option>`).join("");
    const runtimeSummary=runtime?`<div class="runtime-summary"><span class="health health-${health.toLowerCase()}">${healthLabel(health)}</span><span>${runtime.playback_state==="PLAYING"?"Tocando":runtime.playback_state}</span><span>Sync: ${runtime.sync_state}</span><span>Livre: ${formatBytes(runtime.free_storage_bytes)}${isStorageLow(runtime)?" · BAIXO":""}</span></div>`:"";
    const actions=paired?`<section class="device-actions"><h4>Ações</h4><div><button class="secondary diagnostic-button" data-device-id="${device!.id}">Diagnóstico</button><button class="secondary command-button" data-screen-id="${screen.id}" data-command="GET_STATUS">Atualizar status</button><button class="secondary command-button" data-screen-id="${screen.id}" data-command="SYNC_NOW">Sincronizar agora</button><button class="secondary command-button" data-screen-id="${screen.id}" data-command="RELOAD_PLAYLIST">Recarregar playlist</button></div><div class="command-history" data-screen-id="${screen.id}">${renderCommandHistory(screen.id)}</div></section>`:"";
    return `<article class="screen-card">
      <div><span class="status presence-${presence.toLowerCase()}">${presenceLabel(presence)}</span>
      <h3>${escape(screen.name)}</h3>
      <p class="device-details">${version}${lastCommunication}<span>Vínculo: ${paired ? "PAIRED" : "SEM PLAYER"}</span></p>${runtimeSummary}</div>
      <label class="playlist-assignment">Playlist publicada<select data-screen-id="${screen.id}" class="playlist-select" ${playlistVersions.length ? "" : "disabled"}>${options}</select></label>
      <button class="secondary pair-button" data-id="${screen.id}" ${paired ? "disabled" : ""}>
        ${paired ? "Player vinculado" : "Vincular Player"}
      </button>${actions}
    </article>`;
  }).join("") || `<div class="empty">Você ainda não criou nenhuma tela.</div>`;
}

function renderDashboard() {
  scanner.stop();
  const cards = renderScreenCards();

  const content = activeArea === "screens" ? renderScreensArea(cards) : activeArea === "media" ? renderMediaArea() : renderPlaylistsArea();
  root.innerHTML = `<header><div class="brand">LOOPIN <small>ADMIN 2.0</small></div>
    <nav class="main-nav"><button data-area="screens" class="text-button ${activeArea==="screens"?"active":""}">Telas</button><button data-area="media" class="text-button ${activeArea==="media"?"active":""}">Mídias</button><button data-area="playlists" class="text-button ${activeArea==="playlists"?"active":""}">Playlists</button></nav>
    <button id="logout" class="text-button">Sair</button></header>
    <main class="dashboard"><div id="notice" class="notice" aria-live="polite"></div>${content}</main><div id="modal-root"></div>`;

  document.querySelector("#logout")!.addEventListener("click", async () => {
    presenceRefresh.stop();
    commandRefresh.stop();
    await logout(supabase);
  });
  document.querySelectorAll<HTMLElement>("[data-area]").forEach(button=>button.addEventListener("click",()=>{activeArea=parseAdminArea(button.dataset.area);renderDashboard();}));
  if(activeArea==="screens") bindScreensArea(); else if(activeArea==="media") bindMediaArea(); else bindPlaylistsArea();
}

function renderScreensArea(cards:string){return `<div class="title-row"><div><h1>Suas telas</h1><p>Gerencie vínculo, presença e conteúdo.</p></div><button id="new-screen">Nova tela</button></div><section class="screen-grid">${cards}</section>`;}
function bindScreensArea(){document.querySelector("#new-screen")!.addEventListener("click",renderCreateScreen);bindScreenGridEvents(document.querySelector<HTMLElement>(".screen-grid")!, {
    pair: (screenId) => {
      selectedScreen = screens.find((item) => item.id === screenId) ?? null;
      if (selectedScreen) renderPairingInput();
    },
    assign: async (screenId, versionId, select) => {
      select.disabled = true;
      try {
        await assignPlaylistVersion(supabase, screenId, versionId);
        screens = await listScreens(supabase);
        renderDashboard();
        notice("Playlist da tela atualizada.", "success");
      } catch (error) { notice((error as Error).message); select.disabled = false; }
    },
  });document.querySelector<HTMLElement>(".screen-grid")!.addEventListener("click",event=>{const target=event.target as Element;const diagnostic=target.closest<HTMLButtonElement>(".diagnostic-button");if(diagnostic?.dataset.deviceId){const device=screens.flatMap(s=>s.devices??[]).find(d=>d.id===diagnostic.dataset.deviceId);if(device)renderDiagnostic(device);return;}const button=target.closest<HTMLButtonElement>(".command-button");if(button?.dataset.screenId&&button.dataset.command)void sendCommand(button,button.dataset.screenId,button.dataset.command as PlayerCommandType);});}

function renderDiagnostic(device:ScreenDevice){const r=device.runtime_status;const events=recentEvents(healthEvents,device.id,50);const row=(label:string,value:unknown)=>`<div><dt>${escape(label)}</dt><dd>${escape(String(value??"—"))}</dd></div>`;modal(`<div class="editor-heading"><div><h2>Diagnóstico</h2><p>Último estado operacional conhecido.</p></div><button class="secondary cancel">Fechar</button></div>${r?`<section class="diagnostic-health health-${diagnosticHealth(r).toLowerCase()}">${healthLabel(diagnosticHealth(r))}</section><dl class="diagnostic-grid">${row("Sessão",r.session_id)}${row("Uptime",formatUptime(r.uptime_ms))}${row("Memória disponível",formatBytes(r.available_memory_bytes)+(r.memory_low?" · BAIXA":""))}${row("Armazenamento",`${formatBytes(r.free_storage_bytes)} livres de ${formatBytes(r.total_storage_bytes)}`)}${row("Playback",r.playback_state)}${row("Cache",r.cache_state)}${row("Sync",r.sync_state)}${row("Playlist ACTIVE",r.active_playlist_id?`${r.active_playlist_id} · v${r.active_playlist_version??"—"}`:"—")}${row("Item atual",[r.current_item_id,r.current_content_kind,r.current_media_type].filter(Boolean).join(" · ")||"—")}${row("Último sync",r.last_sync_at?new Date(r.last_sync_at).toLocaleString("pt-BR"):"Nunca")}${row("Última comunicação",formatLastCommunication(r.last_seen_at))}${row("Último erro",r.last_error_summary?`${r.last_error_code??"ERRO"}: ${r.last_error_summary}`:"Nenhum")}${row("Versão",r.app_version)}</dl>`:`<div class="empty">O Player ainda não enviou diagnóstico.</div>`}<section class="health-history"><h3>Eventos recentes</h3>${events.map(e=>`<div class="health-event severity-${e.severity.toLowerCase()}"><span>${new Date(e.occurred_at).toLocaleString("pt-BR")}</span><strong>${escape(eventLabel(e.event_type))}</strong></div>`).join("")||`<small class="muted">Nenhum evento relevante.</small>`}</section>`);document.querySelector(".cancel")!.addEventListener("click",closeModal);}

async function sendCommand(button:HTMLButtonElement,screenId:string,type:PlayerCommandType){if(type!=="GET_STATUS"&&!window.confirm(`Enviar “${COMMAND_LABELS[type]}” para esta tela?`))return;button.disabled=true;try{const created=await enqueuePlayerCommand(supabase,screenId,type);deviceCommands=[created,...deviceCommands];document.querySelector<HTMLElement>(`.command-history[data-screen-id="${screenId}"]`)!.innerHTML=renderCommandHistory(screenId);commandRefresh.start();notice("Comando enviado; aguardando o Player.","success");}catch(error){notice((error as Error).message);}finally{button.disabled=false;}}

function renderMediaArea(){const cards=mediaAssets.map(a=>`<article class="content-card"><span class="status">${a.media_type}</span><h3>${escape(a.name)}</h3><p>${(a.expected_size_bytes/1048576).toFixed(2)} MiB</p><small>${new Date(a.created_at).toLocaleString("pt-BR")}</small></article>`).join("")||`<div class="empty">Nenhuma mídia enviada.</div>`;return `<div class="title-row"><div><h1>Biblioteca de mídia</h1><p>Arquivos privados prontos para playlists.</p></div><button id="upload-media">Enviar mídia</button></div><input id="media-file" type="file" accept="video/mp4,image/jpeg,image/png" hidden/><div id="upload-state" class="upload-state"></div><section class="content-grid">${cards}</section>`;}
function bindMediaArea(){document.querySelector("#upload-media")!.addEventListener("click",()=>document.querySelector<HTMLInputElement>("#media-file")!.click());document.querySelector<HTMLInputElement>("#media-file")!.addEventListener("change",async event=>{const input=event.currentTarget as HTMLInputElement;const file=input.files?.[0];if(!file)return;const state=document.querySelector<HTMLElement>("#upload-state")!;try{const result=await uploadMediaAsset(supabase,session!.user.id,file,value=>state.textContent=value);mediaAssets=await listMediaAssets(supabase);renderDashboard();notice(result.deduplicated?"Mídia já existente; asset reutilizado.":"Mídia enviada com sucesso.","success");}catch(error){notice((error as Error).message);}finally{input.value="";}});}

function renderPlaylistsArea(){const cards=playlists.map(p=>`<article class="content-card"><h3>${escape(p.name)}</h3><p>${p.player_playlist_drafts?.items?.length??0} itens no rascunho</p><div class="version-list">${(p.player_playlist_versions??[]).sort((a,b)=>b.version_number-a.version_number).map(v=>`<small>v${v.version_number} · ${new Date(v.published_at).toLocaleString("pt-BR")}</small>`).join("")||"<small>Nenhuma versão publicada</small>"}</div><button class="edit-playlist" data-id="${p.id}">Abrir editor</button></article>`).join("")||`<div class="empty">Nenhuma playlist criada.</div>`;return `<div class="title-row"><div><h1>Playlists</h1><p>Edite rascunhos e publique versões imutáveis.</p></div><button id="new-playlist">Nova playlist</button></div><section class="content-grid">${cards}</section>`;}
function bindPlaylistsArea(){document.querySelector("#new-playlist")!.addEventListener("click",renderCreatePlaylist);document.querySelectorAll<HTMLElement>(".edit-playlist").forEach(b=>b.addEventListener("click",()=>openEditor(b.dataset.id!)));}

function renderCreatePlaylist() {
  modal(`<h2>Nova playlist</h2><p>Crie um rascunho vazio para começar.</p>
    <form id="playlist-form"><label>Nome da playlist<input name="name" maxlength="100" required autofocus /></label>
    <div id="modal-notice" class="notice"></div><div class="actions"><button type="button" class="secondary cancel">Cancelar</button><button type="submit">Criar</button></div></form>`);
  document.querySelector(".cancel")!.addEventListener("click", closeModal);
  document.querySelector<HTMLFormElement>("#playlist-form")!.addEventListener("submit", async (event) => {
    event.preventDefault();
    try {
      const name = String(new FormData(event.currentTarget as HTMLFormElement).get("name"));
      const created = await createPlaylist(supabase, session!.user.id, name);
      playlists = await listPlaylists(supabase);
      openEditor(created.id);
    } catch (error) { modalError(error); }
  });
}

function openEditor(playlistId: string) {
  editingPlaylist = playlists.find((playlist) => playlist.id === playlistId) ?? null;
  if (!editingPlaylist) return;
  draftItems = normalizeItems(editingPlaylist.player_playlist_drafts?.items ?? []);
  renderEditor();
}

function renderEditor() {
  const rows = draftItems.map((item, index) => {
    const asset = item.kind === "MEDIA" ? mediaAssets.find((value) => value.id === item.assetId) : null;
    const title = item.kind === "MEDIA" ? (asset?.name ?? "Mídia indisponível") : `Clima · ${item.configuration.city}`;
    const detail = item.kind === "MEDIA"
      ? `${asset?.media_type ?? "MEDIA"}${item.durationMs ? ` · ${item.durationMs / 1000}s` : ""}`
      : `WEATHER · ${item.durationMs / 1000}s · ${item.configuration.lat}, ${item.configuration.lon}`;
    return `<li class="editor-item"><span class="item-order">${index + 1}</span><div><strong>${escape(title)}</strong><small>${escape(detail)}</small></div>
      <div class="item-actions"><button class="secondary item-up" data-index="${index}" aria-label="Mover para cima">↑</button><button class="secondary item-down" data-index="${index}" aria-label="Mover para baixo">↓</button><button class="secondary item-remove" data-index="${index}">Remover</button></div></li>`;
  }).join("") || `<li class="empty">Rascunho vazio. Adicione uma mídia ou clima.</li>`;
  const history = (editingPlaylist!.player_playlist_versions ?? []).sort((a,b) => b.version_number-a.version_number)
    .map((version) => `<small>v${version.version_number} · ${new Date(version.published_at).toLocaleString("pt-BR")}</small>`).join("") || "<small>Nenhuma publicação</small>";
  modal(`<div class="editor-heading"><div><h2>${escape(editingPlaylist!.name)}</h2><p>Rascunho editável; publicações permanecem imutáveis.</p></div><button class="secondary cancel">Fechar</button></div>
    <div class="editor-toolbar"><button id="add-media">Adicionar mídia</button><button id="add-weather" class="secondary">Adicionar clima</button></div>
    <ol class="editor-list">${rows}</ol><div id="modal-notice" class="notice"></div>
    <section class="publication-history"><h3>Histórico publicado</h3>${history}</section>
    <div class="actions"><button id="save-draft" class="secondary">Salvar rascunho</button><button id="publish-draft" ${draftItems.length ? "" : "disabled"}>Publicar versão</button></div>`);
  document.querySelector(".modal")!.classList.add("editor-modal");
  document.querySelector(".cancel")!.addEventListener("click", closeModal);
  document.querySelector("#add-media")!.addEventListener("click", renderAddMedia);
  document.querySelector("#add-weather")!.addEventListener("click", renderAddWeather);
  document.querySelectorAll<HTMLElement>(".item-up").forEach(button => button.addEventListener("click", () => { draftItems = moveItem(draftItems, Number(button.dataset.index), -1); renderEditor(); }));
  document.querySelectorAll<HTMLElement>(".item-down").forEach(button => button.addEventListener("click", () => { draftItems = moveItem(draftItems, Number(button.dataset.index), 1); renderEditor(); }));
  document.querySelectorAll<HTMLElement>(".item-remove").forEach(button => button.addEventListener("click", () => { draftItems = removeItem(draftItems, Number(button.dataset.index)); renderEditor(); }));
  document.querySelector("#save-draft")!.addEventListener("click", () => void persistDraft(false));
  document.querySelector("#publish-draft")!.addEventListener("click", () => void persistDraft(true));
}

function renderAddMedia() {
  const options = mediaAssets.map((asset) => `<option value="${asset.id}">${escape(asset.name)} · ${asset.media_type}</option>`).join("");
  modal(`<h2>Adicionar mídia</h2>${options ? `<form id="add-media-form"><label>Arquivo<select name="assetId">${options}</select></label><label>Duração da imagem (segundos)<input name="duration" type="number" min="1" max="3600" value="10" /></label><p>Vídeos usam a duração natural; o campo acima vale somente para imagens.</p><div id="modal-notice" class="notice"></div><div class="actions"><button type="button" class="secondary back">Voltar</button><button type="submit">Adicionar</button></div></form>` : `<p>Nenhuma mídia disponível. Envie um arquivo na biblioteca primeiro.</p><div class="actions"><button class="secondary back">Voltar</button></div>`}`);
  document.querySelector(".back")!.addEventListener("click", renderEditor);
  document.querySelector<HTMLFormElement>("#add-media-form")?.addEventListener("submit", (event) => {
    event.preventDefault();
    try {
      const data = new FormData(event.currentTarget as HTMLFormElement);
      const asset = mediaAssets.find((value) => value.id === String(data.get("assetId")))!;
      draftItems = normalizeItems([...draftItems, mediaItem(asset, Number(data.get("duration")))]);
      renderEditor();
    } catch (error) { modalError(error); }
  });
}

function renderAddWeather() {
  modal(`<h2>Adicionar clima</h2><form id="add-weather-form"><label>Cidade<input name="city" maxlength="120" required /></label><div class="coordinate-grid"><label>Latitude<input name="lat" type="number" min="-90" max="90" step="any" required /></label><label>Longitude<input name="lon" type="number" min="-180" max="180" step="any" required /></label></div><label>Duração (segundos)<input name="duration" type="number" min="1" max="3600" value="20" required /></label><div id="modal-notice" class="notice"></div><div class="actions"><button type="button" class="secondary back">Voltar</button><button type="submit">Adicionar</button></div></form>`);
  document.querySelector(".back")!.addEventListener("click", renderEditor);
  document.querySelector<HTMLFormElement>("#add-weather-form")!.addEventListener("submit", (event) => {
    event.preventDefault();
    try {
      const data = new FormData(event.currentTarget as HTMLFormElement);
      draftItems = normalizeItems([...draftItems, weatherItem(String(data.get("city")), String(data.get("lat")), String(data.get("lon")), Number(data.get("duration")))]);
      renderEditor();
    } catch (error) { modalError(error); }
  });
}

async function persistDraft(publish: boolean) {
  try {
    await savePlaylistDraft(supabase, editingPlaylist!.id, draftItems);
    const published = publish ? await publishPlaylistDraft(supabase, editingPlaylist!.id) : null;
    [playlists, playlistVersions] = await Promise.all([listPlaylists(supabase), listPublishedPlaylistVersions(supabase)]);
    editingPlaylist = playlists.find((playlist) => playlist.id === editingPlaylist!.id) ?? null;
    if (!editingPlaylist) return closeModal();
    draftItems = normalizeItems(editingPlaylist.player_playlist_drafts?.items ?? []);
    renderEditor();
    modalNotice(published ? `Versão ${published.version_number} publicada e pronta para atribuição.` : "Rascunho salvo.");
  } catch (error) { modalError(error); }
}

function modalError(error: unknown) {
  const target = document.querySelector<HTMLDivElement>("#modal-notice");
  if (!target) return;
  target.textContent = (error as Error).message;
  target.className = "notice error";
}

function modalNotice(message: string) {
  const target = document.querySelector<HTMLDivElement>("#modal-notice");
  if (!target) return;
  target.textContent = message;
  target.className = "notice success";
}

function modal(content: string) {
  document.querySelector("#modal-root")!.innerHTML = `<div class="modal-backdrop"><section class="modal">${content}</section></div>`;
}

function closeModal() {
  scanner.stop();
  proof = null;
  selectedScreen = null;
  document.querySelector("#modal-root")!.innerHTML = "";
}

function renderCreateScreen() {
  modal(`<h2>Nova tela</h2><p>Dê um nome claro para identificar o local.</p>
    <form id="screen-form"><label>Nome da tela<input name="name" maxlength="100" required autofocus /></label>
    <div id="modal-notice" class="notice"></div><div class="actions"><button type="button" class="secondary cancel">Cancelar</button>
    <button type="submit">Criar tela</button></div></form>`);
  document.querySelector(".cancel")!.addEventListener("click", closeModal);
  document.querySelector<HTMLFormElement>("#screen-form")!.addEventListener("submit", async (event) => {
    event.preventDefault();
    const formElement = event.currentTarget as HTMLFormElement;
    try {
      const data = new FormData(formElement);
      const created = await createScreen(supabase, session!.user.id, String(data.get("name")));
      screens.unshift(created);
      closeModal();
      renderDashboard();
      notice("Tela criada com sucesso.", "success");
    } catch (error) {
      const target = document.querySelector<HTMLDivElement>("#modal-notice")!;
      target.textContent = (error as Error).message;
      target.className = "notice error";
    }
  });
}

function renderPairingInput() {
  modal(`<h2>Vincular Player</h2><p>Tela selecionada: <strong>${escape(selectedScreen!.name)}</strong></p>
    <div class="tabs"><button id="code-tab" class="tab active">Digitar código</button><button id="qr-tab" class="tab">Escanear QR Code</button></div>
    <div id="pairing-content"><form id="code-form"><label>Código de 6 dígitos
      <input name="code" class="code-input" inputmode="numeric" maxlength="6" pattern="[0-9]{6}" placeholder="000000" required autofocus /></label>
      <div id="modal-notice" class="notice"></div><div class="actions"><button type="button" class="secondary cancel">Cancelar</button>
      <button type="submit">Continuar</button></div></form></div>`);
  bindCodeForm();
  document.querySelector("#code-tab")!.addEventListener("click", renderPairingInput);
  document.querySelector("#qr-tab")!.addEventListener("click", renderScanner);
}

function bindCodeForm() {
  document.querySelector(".cancel")!.addEventListener("click", closeModal);
  document.querySelector<HTMLFormElement>("#code-form")!.addEventListener("submit", (event) => {
    event.preventDefault();
    const formElement = event.currentTarget as HTMLFormElement;
    try {
      proof = parsePairingCode(String(new FormData(formElement).get("code")));
      renderConfirmation();
    } catch (error) {
      const target = document.querySelector<HTMLDivElement>("#modal-notice")!;
      target.textContent = (error as Error).message;
      target.className = "notice error";
    }
  });
}

function renderScanner() {
  modal(`<h2>Escanear QR Code</h2><p>Aponte a câmera para o QR exibido pelo Player.</p>
    <video id="scanner-video" playsinline></video><div id="modal-notice" class="notice"></div>
    <div class="actions"><button type="button" class="secondary cancel">Cancelar</button></div>`);
  document.querySelector(".cancel")!.addEventListener("click", closeModal);
  scanner.start(
    document.querySelector<HTMLVideoElement>("#scanner-video")!,
    (value) => {
      try {
        proof = parsePairingQr(value);
        renderConfirmation();
        return true;
      }
      catch (error) {
        const target = document.querySelector<HTMLDivElement>("#modal-notice")!;
        target.textContent = (error as Error).message;
        target.className = "notice error";
        return false;
      }
    },
    (message) => {
      const target = document.querySelector<HTMLDivElement>("#modal-notice")!;
      target.textContent = message;
      target.className = "notice error";
    },
  );
}

function renderConfirmation() {
  scanner.stop();
  modal(`<h2>Confirmar vínculo</h2><p>Você está vinculando um novo Player à tela:</p>
    <div class="confirm-screen">${escape(selectedScreen!.name)}</div><p>Confirmar?</p>
    <div id="modal-notice" class="notice"></div><div class="actions"><button class="secondary cancel">Cancelar</button>
    <button id="confirm-pair">Confirmar vínculo</button></div>`);
  document.querySelector(".cancel")!.addEventListener("click", closeModal);
  document.querySelector<HTMLButtonElement>("#confirm-pair")!.addEventListener("click", async (event) => {
    const button = event.currentTarget as HTMLButtonElement;
    button.disabled = true;
    try {
      const result = await pairPlayer(supabase, selectedScreen!.id, proof!);
      proof = null;
      screens = await listScreens(supabase);
      modal(`<div class="success-mark">✓</div><h2>Player vinculado com sucesso.</h2>
        <dl><dt>Tela</dt><dd>${escape(result.screen_name)}</dd><dt>Estado</dt><dd>PAIRED</dd>
        <dt>Player</dt><dd class="device-id">${escape(result.device_id)}</dd></dl>
        <div class="actions"><button id="finish">Concluir</button></div>`);
      document.querySelector("#finish")!.addEventListener("click", () => { closeModal(); renderDashboard(); });
    } catch (error) {
      const target = document.querySelector<HTMLDivElement>("#modal-notice")!;
      target.textContent = (error as Error).message;
      target.className = "notice error";
      button.disabled = false;
      proof = null;
    }
  });
}

supabase.auth.onAuthStateChange((_event, nextSession) => {
  session = nextSession;
  if (session) void loadDashboard(); else renderLogin();
});

void supabase.auth.getSession().then(({ data }) => {
  session = data.session;
  if (session) void loadDashboard(); else renderLogin();
});
