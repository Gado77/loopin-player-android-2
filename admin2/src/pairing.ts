import type { PairingProof } from "./types";

const TOKEN = /^[A-Za-z0-9_-]{43}$/;

export function parsePairingCode(value: string): PairingProof {
  const code = value.replace(/\s/g, "");
  if (!/^\d{6}$/.test(code)) {
    throw new Error("Digite exatamente os 6 números exibidos no Player.");
  }
  return { kind: "code", code };
}

export function parsePairingQr(value: string): PairingProof {
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    throw new Error("Este QR Code não pertence ao Loopin Player.");
  }

  const version = url.searchParams.get("v");
  const type = url.searchParams.get("type");
  const token = url.searchParams.get("token") ?? "";
  if (
    url.protocol !== "loopin:" ||
    url.hostname !== "pair" ||
    version !== "1" ||
    type !== "loopin-device-pairing" ||
    !TOKEN.test(token)
  ) {
    throw new Error("QR Code inválido ou incompatível com o Loopin Player 2.0.");
  }
  return { kind: "token", token };
}

export function pairingErrorMessage(code: string): string {
  const messages: Record<string, string> = {
    authentication_required: "Sua sessão expirou. Entre novamente.",
    invalid_pairing_proof: "O código ou QR Code é inválido.",
    pairing_expired_or_consumed: "O código expirou ou já foi utilizado. Gere um novo código no Player.",
    pairing_not_available: "Este pareamento não está mais disponível.",
    device_already_paired: "Este Player já está vinculado a uma tela.",
    forbidden_screen: "Você não tem permissão para vincular esta tela.",
    screen_unavailable: "A tela não existe ou está desativada.",
    rate_limited: "Muitas tentativas. Aguarde alguns minutos e tente novamente.",
  };
  return messages[code] ?? "Não foi possível concluir o vínculo. Verifique a conexão e tente novamente.";
}
