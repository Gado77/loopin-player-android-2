# Fase 8.1 — Pareamento seguro por código e QR Code

## Estado

Implementação de código concluída em 26/08/2026. A validação integrada permanece **PENDENTE DE DEPLOY** da migration e da Edge Function `device-pairing` no Supabase. Não existe fallback local que aceite pareamento sem o backend.

## Separação de identidades

- `internalId`: identidade estável local; somente seu SHA-256 participa do bootstrap.
- `pairing_token`: 256 bits aleatórios, temporário, armazenado no backend somente como SHA-256.
- `pairing_code`: seis dígitos, temporário, válido na mesma janela do token.
- `device credential`: segredo aleatório de 256 bits criado e persistido no Player; somente seu SHA-256 é enviado ao backend.
- `device_id`: UUID permanente criado pelo backend depois da confirmação.

Código e QR nunca viram identidade permanente nem senha do dispositivo.

## Fluxo

1. Player UNPAIRED cria segredo local e envia hashes de identidade/credencial.
2. Backend cria token opaco, código único na janela e expiração de 30 segundos.
3. Player mostra código e QR `loopin://pair?token=...` simultaneamente.
4. Enquanto UNPAIRED, Player consulta somente o status da sessão temporária.
5. Admin autenticado lê QR ou recebe o código e pede confirmação do nome da tela.
6. RPC com `FOR UPDATE` valida expiração/consumo e cria `screen` + `player_device` atomicamente.
7. Sessão é consumida e outras sessões do mesmo Player expiram.
8. Player recebe o UUID, persiste o estado PAIRED e inicia playback.

## Segurança implementada

- tabelas de pairing sem grants para `anon`/`authenticated`;
- operações privilegiadas somente na Edge Function com service role;
- confirmação exige usuário Supabase autenticado;
- token armazenado somente como hash;
- segredo permanente nunca entra no QR nem retorna do backend;
- limite por origem/dispositivo e por usuário;
- exclusão GiST impede códigos iguais em janelas sobrepostas;
- consumo atômico com bloqueio de linha;
- rejeição de expirado, consumido, replay e dispositivo já pareado;
- resposta `no-store`;
- sem chave anon embutida no Player;
- sem polling depois de PAIRED.

## Comportamento offline

- UNPAIRED offline não exibe código falso nem expirado; aguarda rede e tenta novamente com intervalo limitado.
- PAIRED offline não abre a tela de pairing e inicia a playlist/cache local normalmente.
- force-stop/reboot preservam estado, UUID e segredo no sandbox privado do aplicativo.

## Dependência adicionada

`com.google.zxing:core:3.5.4`, apenas o encoder QR. Não há câmera, WebView ou biblioteca de UI adicionada ao Player.

## Validação executada

- 168 testes Android/JVM aprovados;
- Android Lint aprovado, sem erros e com quatro avisos preexistentes de recursos de debug/previsão não utilizados;
- `assembleDebug` e `assembleRelease` aprovados;
- build de produção do Admin aprovado;
- ESLint dos arquivos novos/alterados aprovado;
- lint global do Admin permanece bloqueado por 12.329 achados preexistentes, majoritariamente CRLF/Prettier;
- LDPlayer não estava conectado durante a tentativa de validação desta etapa.

## Não validado

- migration aplicada no banco remoto;
- Edge Function implantada;
- rotação real de código/QR contra relógio do backend;
- confirmação manual e via câmera;
- disputa simultânea e replay no banco real;
- force-stop/reboot após pareamento real no LDPlayer.

Esses itens são critérios de aceite e impedem considerar a fase operacionalmente concluída antes do deploy controlado.
