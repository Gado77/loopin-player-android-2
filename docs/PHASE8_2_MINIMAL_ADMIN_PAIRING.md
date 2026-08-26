# Fase 8.2 — Admin mínimo e pareamento real

## Escopo e isolamento

Implementação realizada exclusivamente em `LoopinPlayer2/admin2` e no backend Supabase próprio do Player 2 (`zdhsfirabkmivuzwyids`). O Admin antigo, o Player de produção, o WEATHER, o playback, a playlist e o cache não foram alterados.

Não existia frontend do Admin 2.0 no repositório. Foi criada uma aplicação web mínima em TypeScript/Vite, sem framework visual e sem funcionalidades fora do pareamento.

## Contratos preservados

Edge Function `device-pairing`:

- `create`/`start`: Player cria sessão temporária;
- `status`: Player consulta a sessão pelo token opaco;
- `confirm`: Admin autenticado confirma por `pairing_code` ou `pairing_token` e `screen_id`;
- `heartbeat`: reservado ao dispositivo autenticado.

O QR passou a declarar explicitamente sua versão e tipo:

```text
loopin://pair?v=1&type=loopin-device-pairing&token=<token-base64url-43>
```

O Admin rejeita qualquer outro esquema, host, versão, tipo ou token. O token nunca é salvo em storage ou banco pelo frontend.

## Autenticação e telas

- Supabase Auth por email/senha;
- sessão persistente e renovação automática pelo SDK público;
- logout validado;
- listagem de telas limitada pelas políticas RLS existentes;
- criação com `owner_id` do usuário autenticado;
- status `ACTIVE` e indicação `PAIRED`/`SEM PLAYER`;
- nenhuma service-role no frontend ou no Git.

## Confirmação atômica

A RPC `confirm_device_pairing` existente continua sendo o ponto transacional. Ela bloqueia sessão e dispositivo com `FOR UPDATE` e, na mesma transação:

1. valida sessão, expiração e consumo;
2. valida que a tela ativa pertence ao usuário;
3. grava o hash da credencial permanente criada no Player;
4. vincula dispositivo e tela;
5. consome a sessão;
6. expira sessões concorrentes;
7. registra o evento operacional.

O segredo permanente é criado e persistido no sandbox do Player antes do pareamento; somente seu SHA-256 chega ao backend. Após a confirmação, o Player recebe o UUID e a atribuição da tela e mantém o segredo original. Ele não depende do código ou QR como identidade permanente.

## Testes automatizados

- Admin: 21 testes Vitest aprovados;
- Android/JVM: 168 testes aprovados;
- Android Lint aprovado;
- `assembleDebug` e `assembleRelease` aprovados;
- Admin `tsc` + Vite build aprovados;
- `npm audit`: zero vulnerabilidades.

Os testes do Admin cobrem login, erro de login, configuração de persistência, criação/listagem de telas, código válido/inválido, QR válido e formatos inválidos, payload enviado à Edge Function e mensagens seguras para autenticação, expiração/reutilização, Player já pareado, propriedade e rede.

## Validação remota

| Cenário | Resultado |
|---|---:|
| código inválido | HTTP 400 |
| confirmação anônima | HTTP 401 |
| tela pertencente a outro usuário | HTTP 403 |
| código válido | HTTP 200 / PAIRED |
| código reutilizado | HTTP 410 |
| Player já pareado | HTTP 409 |
| QR versionado válido | HTTP 200 / PAIRED |
| sessão expirada após 30 segundos | HTTP 410 |

Todos os dispositivos, telas e usuários sintéticos dessa matriz foram removidos ao final.

## Validação no LDPlayer

Executado no LDPlayer 9, resolução configurada em 900 × 1600:

1. APK debug instalado;
2. dados anteriores limpos para garantir `UNPAIRED`;
3. Player exibiu código de seis dígitos, QR e contador regressivo;
4. login real no Admin aprovado;
5. persistência da sessão após reload aprovada;
6. tela `LDPlayer Laboratório` criada pela interface;
7. confirmação visual exibida;
8. vínculo real por código concluído;
9. Admin exibiu `PAIRED` e o UUID do Player;
10. Player persistiu `state=PAIRED`, `screen_id`, nome e `device_id`;
11. force-stop e abertura preservaram `PAIRED` sem gerar novo código;
12. reboot completo do LDPlayer preservou `PAIRED` sem gerar novo código;
13. reinício sem rede preservou o estado local e não abriu novo pareamento;
14. conta, tela, dispositivo e dados de laboratório foram removidos com segurança após a prova; o APK foi devolvido ao estado limpo.

## Limitação real restante

O contrato QR, seu parser, os formatos inválidos e uma confirmação remota real por token foram validados. O scanner de câmera foi implementado, porém não foi possível apontar uma câmera física para o QR mostrado no próprio LDPlayer durante a automação. Assim, a captura óptica `câmera → QR do LDPlayer` continua pendente de uma câmera/webcam ou segundo dispositivo disponível.

O Supabase Security Advisor não encontrou problemas nas funções/tabelas de pairing, mas passou a recomendar habilitar **Leaked Password Protection** no Auth. Essa proteção é configuração do projeto/plano, não uma falha no código entregue.

## Estado de conclusão

O fluxo real por código está concluído de ponta a ponta e sobrevive a force-stop, reboot e inicialização offline. O fluxo QR está concluído no contrato, frontend e backend; somente a prova física do scanner permanece pendente.
