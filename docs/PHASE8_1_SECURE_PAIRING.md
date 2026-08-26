# Fase 8.1 — Pareamento seguro por código e QR Code

## Estado

Implementação e deploy controlado concluídos em 26/08/2026 no projeto Supabase isolado **Loopin Player 2.0** (`zdhsfirabkmivuzwyids`). O Admin e o Player de produção não foram alterados. Não existe fallback local que aceite pareamento sem o backend.

## Separação de identidades

- `internalId`: UUID estável e persistente usado como identidade técnica do dispositivo.
- `friendlyCode`: código local persistente de seis dígitos, exibível, mas nunca usado como substituto do UUID interno.
- `pairing_token`: 256 bits aleatórios, temporário, armazenado no backend somente como SHA-256.
- `pairing_code`: seis dígitos, temporário, válido na mesma janela do token.
- `device credential`: segredo aleatório de 256 bits criado e persistido no Player; somente seu SHA-256 é enviado ao backend.
- `device_id`: UUID permanente criado pelo backend depois da confirmação.

Código e QR nunca viram identidade permanente nem senha do dispositivo.

## Fluxo

1. Player UNPAIRED cria segredo local, envia o UUID interno persistente, o código amigável separado e somente o hash SHA-256 da credencial.
2. Backend cria token opaco, código único na janela e expiração de 30 segundos.
3. Player mostra código e QR versionado `loopin://pair?v=1&type=loopin-device-pairing&token=...` simultaneamente.
4. Enquanto UNPAIRED, Player consulta somente o status da sessão temporária.
5. Admin autenticado lê QR ou recebe o código e pede confirmação do nome da tela.
6. RPC com `FOR UPDATE` valida expiração/consumo, cria ou seleciona `screen`, grava a credencial e vincula o `device` atomicamente.
7. Sessão é consumida e outras sessões do mesmo Player expiram.
8. Player recebe o UUID, persiste o estado PAIRED e inicia playback.

## Segurança implementada

- tabelas de pairing sem grants para `anon`/`authenticated`;
- operações privilegiadas somente na Edge Function com service role;
- confirmação exige usuário Supabase autenticado;
- token armazenado somente como hash;
- segredo permanente nunca entra no QR nem é enviado ao backend; apenas seu SHA-256 é persistido;
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

## Deploy e validação executados

- migrations `20260826190000_player2_secure_pairing.sql` e `20260826200000_harden_pairing_function_permissions.sql` aplicadas no projeto novo;
- Edge Function `device-pairing` publicada com `verify_jwt=false`; as ações públicas são limitadas e `confirm` valida explicitamente o JWT;
- fluxo remoto `create → status` aprovado com código temporário de seis dígitos e limpeza posterior dos dados de laboratório;
- confirmação anônima rejeitada com HTTP 401;
- Supabase Database Security Advisor aprovado sem achados após o endurecimento das permissões;
- testes Android/JVM aprovados;
- Android Lint aprovado;
- `assembleDebug` e `assembleRelease` aprovados;
- o endpoint do APK aponta exclusivamente para `https://zdhsfirabkmivuzwyids.supabase.co/functions/v1/device-pairing`;
- nenhum arquivo do Admin ou do Player antigo foi alterado.

## Não validado

- confirmação autenticada manual e via câmera, dependente do futuro Admin/usuário autenticado deste projeto;
- disputa simultânea e replay no banco real;
- force-stop/reboot após pareamento real no LDPlayer.

O backend e o Player estão implantados e compilados. Os itens restantes são critérios de aceite do fluxo completo quando o cliente administrativo do projeto novo existir.
