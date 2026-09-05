# Fase 14 — Instalação OTA e recuperação pós-update

## Resultado

A Fase 14 completa a fronteira `READY_TO_INSTALL → autorização remota → PackageInstaller → verificação no novo processo`. O fluxo permanece secundário ao playback e nunca instala no fim do download. A build normal é `versionCode 14`, `2.0.0-phase14-update-install`.

## Arquitetura e contrato

- `PlayerUpdateManager` continua responsável por tamanho, SHA-256, package, versionCode e certificado. O artefato READY e seus metadados agora sobrevivem a restart em `loopin_prepared_update` e são integralmente revalidados antes de cada tentativa.
- `UpdateInstallCoordinator` exige Player pareado, impede tentativa concorrente, chama `authorize_install`, persiste a tentativa e só então entrega o arquivo ao instalador.
- `AndroidPackageUpdateInstaller` usa `PackageInstaller.Session`, streaming em buffer, `fsync` e `commit(IntentSender)`. Não usa shell, ADB, root, reflexão ou API oculta.
- `UpdateInstallResultReceiver` trata sucesso, pending user action, aborto, bloqueio, conflito, incompatibilidade, APK inválido, storage e falha genérica com códigos curtos.
- `PendingInstallConfirmationBridge` entrega a Intent oficial do Android somente quando `MainActivity` está em foreground. “Agora não” preserva o APK. Após perda do processo, uma nova solicitação recria a sessão; Intents não são persistidas como autoridade.
- `PackageReplacedReceiver` registra `MY_PACKAGE_REPLACED` sem trabalho pesado. O startup também verifica a tentativa persistida.

## Capacidade e unknown sources

Estados detectados: `INTERACTIVE_READY`, `INTERACTIVE_PERMISSION_REQUIRED`, `DEVICE_OWNER` e `UNAVAILABLE`. `DEVICE_OWNER` depende exclusivamente de `DevicePolicyManager.isDeviceOwnerApp`. Em API 26+, `canRequestPackageInstalls()` determina a necessidade de autorização; a Activity abre a tela oficial `ACTION_MANAGE_UNKNOWN_APP_SOURCES`. API 21–25 não chama essas APIs novas. Em API 31+, `USER_ACTION_NOT_REQUIRED` só é pedido quando o app é realmente Device Owner; `STATUS_PENDING_USER_ACTION` continua autoritativo.

## Comando e segurança

`INSTALL_UPDATE` entrou na whitelist sem payload. Ele não aceita release, URL, caminho, package, flags ou shell: significa apenas instalar o único APK previamente preparado. O backend cria uma tentativa para a tela própria somente quando o runtime informa READY e uma release PUBLISHED do mesmo canal/versão existe. Antes da sessão, `player-update action=authorize_install` revalida release, status, canal, package e monotonicidade usando a credencial permanente.

Credenciais, hashes de credencial, URL assinada, tokens administrativos, Intent completa e caminhos de keystore não são persistidos remotamente nem logados.

## Estados, persistência e pós-update

A tentativa pequena em SharedPreferences contém release, versões origem/alvo, versão nominal, SHA, início, estado e código de falha. Estados: `INSTALL_REQUESTED`, `INSTALL_PERMISSION_REQUIRED`, `USER_ACTION_REQUIRED`, `INSTALLING`, `POST_UPDATE_VERIFYING`, `INSTALLED`, `INSTALL_DEFERRED`, `INSTALL_CANCELED`, `INSTALL_FAILED` e `UPDATE_RECOVERY_REQUIRED`.

No novo processo, sucesso só é considerado verificável quando versionCode atual coincide com o alvo e identidade, pairing/credential e store ACTIVE/PREVIOUS são legíveis. O heartbeat existente é antecipado; o backend fecha a tentativa como INSTALLED ao receber a target version. Não há scheduler, polling, service ou wakelock novo. Sem rede, startup e ACTIVE local continuam independentes.

## Supabase e RLS

`device_update_attempts` mantém uma tentativa idempotente por device/release, timestamps operacionais e failure code limitado. Usuário autenticado lê somente tentativas de telas próprias; anon não lê; Admin não escreve diretamente. `report_device_update_attempt` é restrita a service role e valida transições. O Player acessa somente a Edge Function autenticada.

As migrations `20260905140000_phase14_ota_install.sql` e `20260905141000_phase14_pending_action_transition.sql` foram aplicadas no projeto isolado `zdhsfirabkmivuzwyids`. `player-update` e `device-pairing` foram publicados. Request sem Authorization para autorização de instalação retornou HTTP 401. Nenhum sistema de produção foi alterado.

## Admin 2

O card da tela mostra versão atual/preparada, capacidade, estado, falha recente e botão “Instalar atualização” somente para READY superior e sem instalação ativa. Há confirmação por tela; não existe ação em massa. O diagnóstico mostra canal, update, versão preparada, capacidade e resultado. Estados em instalação/verificação acima de quinze minutos são apresentados como possível falha pós-update.

## Testes e validações

- Baseline: 220 Android/JVM e 84 Admin.
- Fase 14: 229 Android/JVM e 88 Admin; unitários cobrem ausência de READY, READY válido, autorização negada/revogação, persistência antes do installer, permissão, falha/storage, Player não pareado, target exata, mismatch e recuperação local.
- `test`, `lintDebug`, `assembleDebug` e `assembleRelease`: aprovados.
- Vitest, TypeScript/Vite build e `npm audit`: aprovados; zero vulnerabilidades.
- LDPlayer: APK v14 instalado, versão/package/permissão conferidos, Activity em foreground, processo vivo, force-stop/reabertura e reboot executados sem crash/ANR/OOM. SharedPreferences de identidade, pairing e credential permaneceram após update de v13 para v14 e reboot. O LDPlayer não relançou o processo automaticamente nesse reboot; a abertura explícita iniciou normalmente. O laboratório não possuía ACTIVE/PREVIOUS no início desta rodada, portanto sua preservação não foi reivindicada como E2E.

## Limitações honestas

O E2E remoto completo v14→v15 pelo PackageInstaller não pôde ser concluído nesta rodada porque não há identidade `release_admin` LAB nem `LOOPIN_RELEASE_INSPECTOR_TOKEN` provisionados, e a chave definitiva de produção continua ausente. Esses segredos não foram inventados nem versionados. Assim, upload/inspeção/publicação, confirmação real na UI do PackageInstaller, cancelamento interativo e fechamento remoto v15 permanecem pendentes. A implementação e as fronteiras automatizadas estão presentes, mas isso não deve ser descrito como prova E2E.

Não há rollback/downgrade automático. Uma versão instalada que não volte saudável permanece `POST_UPDATE_VERIFYING`/`UPDATE_RECOVERY_REQUIRED`. Instalação gerenciada na MXQ: **PENDENTE — VALIDAÇÃO FÍSICA**. Não afirmar instalação silenciosa.

## Arquivos principais

- `core/sync/.../UpdateInstallation.kt`, `PlayerUpdate.kt` e testes;
- `app/.../AndroidUpdateInstaller.kt`, `PlayerUpdateRuntime.kt`, `LoopinApplication.kt`, `OperationsRuntime.kt`, `MainActivity.kt`, manifest e build;
- `core/operations/.../RemoteCommands.kt`, `DeviceOperations.kt` e teste;
- `supabase/migrations/20260905140000_phase14_ota_install.sql`;
- `supabase/functions/player-update/index.ts`, `device-pairing/index.ts`;
- `admin2/src/types.ts`, `api.ts`, `commands.ts`, `updates.ts`, `main.ts`, estilos e testes.
