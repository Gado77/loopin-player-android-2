import { describe, expect, it } from "vitest";
import { authOptions } from "./auth-config";
import { pairingErrorMessage, parsePairingCode, parsePairingQr } from "./pairing";

const token = "A".repeat(43);
const validQr = `loopin://pair?v=1&type=loopin-device-pairing&token=${token}`;

describe("pairing input", () => {
  it("aceita código válido", () => expect(parsePairingCode("582731")).toEqual({ kind: "code", code: "582731" }));
  it("normaliza espaços no código", () => expect(parsePairingCode("582 731")).toEqual({ kind: "code", code: "582731" }));
  it("rejeita código inválido", () => expect(() => parsePairingCode("12345x")).toThrow(/6 números/));
  it("aceita QR válido", () => expect(parsePairingQr(validQr)).toEqual({ kind: "token", token }));
  it("rejeita QR de outro tipo", () => expect(() => parsePairingQr(`https://example.com/?token=${token}`)).toThrow(/inválido/));
  it("rejeita QR sem versão", () => expect(() => parsePairingQr(`loopin://pair?type=loopin-device-pairing&token=${token}`)).toThrow(/inválido/));
  it("rejeita QR com token malformado", () => expect(() => parsePairingQr("loopin://pair?v=1&type=loopin-device-pairing&token=x")).toThrow(/inválido/));
});

describe("mensagens seguras", () => {
  it("traduz expiração e reutilização", () => expect(pairingErrorMessage("pairing_expired_or_consumed")).toMatch(/expirou|utilizado/));
  it("traduz Player já pareado", () => expect(pairingErrorMessage("device_already_paired")).toMatch(/já está vinculado/));
  it("traduz falta de permissão", () => expect(pairingErrorMessage("forbidden_screen")).toMatch(/permissão/));
  it("traduz sessão sem autenticação", () => expect(pairingErrorMessage("authentication_required")).toMatch(/sessão expirou/));
  it("traduz sessão consumida", () => expect(pairingErrorMessage("pairing_not_available")).toMatch(/não está mais disponível/));
  it("traduz falha de rede sem detalhe técnico", () => expect(pairingErrorMessage("network_error")).toMatch(/conexão/));
  it("não expõe erro técnico desconhecido", () => expect(pairingErrorMessage("postgres_failure_42")).not.toContain("postgres"));
});

describe("sessão administrativa", () => {
  it("persiste e renova a sessão pública do Supabase", () => {
    expect(authOptions.persistSession).toBe(true);
    expect(authOptions.autoRefreshToken).toBe(true);
    expect(authOptions.storageKey).toBe("loopin-player2-admin-auth");
  });
});
