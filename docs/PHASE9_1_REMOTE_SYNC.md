# Fase 9.1 — sincronização remota

O Player pareado consulta `player-manifest` com sua credencial permanente, usa ETag como identidade do snapshot e trata 204, 304, 401, 409, offline e falhas retryable separadamente. Um manifesto 200 é decodificado estritamente como schema 2.

Itens MEDIA são autorizados individualmente em `player-media`. A função comprova que o asset pertence à versão atualmente atribuída à tela e gera URL assinada por 15 minutos no bucket privado `player2-media`. A URL não é persistida nem registrada. O download é streaming e passa pelo cache transacional existente: `.part`, tamanho, SHA-256, staging READY e commit atômico ACTIVE/PREVIOUS. WEATHER nunca é baixado.

O JobScheduler permanece one-shot, exige rede, usa ciclo normal de cinco minutos e backoff de 1, 5, 15 e 30 minutos; 401 reduz a frequência para uma hora. 204 preserva ACTIVE. Toda falha preserva a playlist anterior e o boot/playback offline continuam independentes da rede.

O fallback bundled é usado somente quando não existe ACTIVE. Uma playlist ACTIVE schema 2 é reproduzida exatamente na ordem recebida, sem WEATHER artificial.

## Segurança

Credencial, hash da credencial, URL assinada, service role e tokens não entram em logs nem no Git. O bucket é privado e a fronteira privilegiada fica nas Edge Functions.

## Validação e limitações

Testes JVM, lint e builds debug/release são a validação automatizada. A validação real do Supabase e do LDPlayer deve registrar separadamente os cenários efetivamente executados; LDPlayer não certifica MXQ. Upload/editor de playlist, campanhas, comandos, OTA e mudanças visuais de WEATHER permanecem fora do escopo.
