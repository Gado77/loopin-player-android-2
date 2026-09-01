import "./styles.css";
import type { Session } from "@supabase/supabase-js";
import { assignPlaylistVersion, createScreen, listPublishedPlaylistVersions, listScreens, login, logout, pairPlayer } from "./api";
import { parsePairingCode, parsePairingQr } from "./pairing";
import { PairingScanner } from "./scanner";
import { classifyPresence, formatLastCommunication, presenceLabel } from "./presence";
import { DashboardPresenceRefresher } from "./refresh";
import { bindScreenGridEvents } from "./screen-grid-events";
import { supabase } from "./supabase";
import type { PairingProof, PlaylistVersion, Screen } from "./types";

const root = document.querySelector<HTMLDivElement>("#app")!;
const scanner = new PairingScanner();
let session: Session | null = null;
let screens: Screen[] = [];
let selectedScreen: Screen | null = null;
let proof: PairingProof | null = null;
let playlistVersions: PlaylistVersion[] = [];
const presenceRefresh = new DashboardPresenceRefresher(
  refreshDashboardData,
  () => document.visibilityState === "visible",
);

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
    [screens, playlistVersions] = await Promise.all([listScreens(supabase), listPublishedPlaylistVersions(supabase)]);
    renderDashboard();
  } catch (error) {
    renderDashboard();
    notice((error as Error).message);
  }
  presenceRefresh.start();
}

async function refreshDashboardData() {
  if (!session) return;
  screens = await listScreens(supabase);
  const grid = document.querySelector<HTMLElement>(".screen-grid");
  if (grid) grid.innerHTML = renderScreenCards();
}

function renderScreenCards() {
  return screens.map((screen) => {
    const device = screen.devices?.find((item) => item.pairing_status === "PAIRED") ?? screen.devices?.[0];
    const paired = device?.pairing_status === "PAIRED";
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
    return `<article class="screen-card">
      <div><span class="status presence-${presence.toLowerCase()}">${presenceLabel(presence)}</span>
      <h3>${escape(screen.name)}</h3>
      <p class="device-details">${version}${lastCommunication}<span>Vínculo: ${paired ? "PAIRED" : "SEM PLAYER"}</span></p></div>
      <label class="playlist-assignment">Playlist publicada<select data-screen-id="${screen.id}" class="playlist-select" ${playlistVersions.length ? "" : "disabled"}>${options}</select></label>
      <button class="secondary pair-button" data-id="${screen.id}" ${paired ? "disabled" : ""}>
        ${paired ? "Player vinculado" : "Vincular Player"}
      </button>
    </article>`;
  }).join("") || `<div class="empty">Você ainda não criou nenhuma tela.</div>`;
}

function renderDashboard() {
  scanner.stop();
  const cards = renderScreenCards();

  root.innerHTML = `<header><div class="brand">LOOPIN <small>ADMIN 2.0</small></div>
    <button id="logout" class="text-button">Sair</button></header>
    <main class="dashboard"><div class="title-row"><div><h1>Suas telas</h1><p>Gerencie o vínculo dos seus Players.</p></div>
    <button id="new-screen">Nova tela</button></div><div id="notice" class="notice" aria-live="polite"></div>
    <section class="screen-grid">${cards}</section></main><div id="modal-root"></div>`;

  document.querySelector("#logout")!.addEventListener("click", async () => {
    presenceRefresh.stop();
    await logout(supabase);
  });
  document.querySelector("#new-screen")!.addEventListener("click", renderCreateScreen);
  bindScreenGridEvents(document.querySelector<HTMLElement>(".screen-grid")!, {
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
  });
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
