# Fase 5 — Operação local do dispositivo

## Escopo

A Fase 5 adiciona uma camada operacional local ao Loopin Player 2.0. Não há API, Supabase, Admin, MQTT, WebSocket, polling, captura remota ou execução real de comandos. A inicialização, identidade, diagnóstico, cache e reprodução continuam independentes da rede.

## Arquitetura

`core:operations` é Kotlin/JVM e contém somente contratos, modelos e regras pequenas. O módulo não conhece Android, UI ou transporte remoto. O `app` fornece persistência em `SharedPreferences`, métricas Android sob demanda e a interface de suporte.

Componentes:

- `DevicePairingManager`: máquina de estados `UNPAIRED`, `PAIRING`, `PAIRED` e `PAIRING_ERROR`;
- `DeviceHealthManager`: coleta explicitamente solicitada e guarda apenas o último snapshot em memória;
- `HeartbeatSource`: cria um heartbeat local a partir de um snapshot novo;
- `CommandExecutor`: fronteira futura; comandos conhecidos ficam `Deferred` e desconhecidos são `Unsupported`;
- `OperationalUpdateManager`: projeta a fundação segura da Fase 4 em estados operacionais visíveis;
- `OperationalStateRegistry`: mantém playback, cache, sync, último sync e último erro sem thread própria.

## Diagnóstico

A interface técnica permanece `GONE` durante operação normal. Pode ser alternada localmente pelas teclas `INFO`, `GUIDE` ou `F1`, sem sair do kiosk. Ao abrir, uma única coleta informa:

- código amigável e versão;
- online/offline;
- estado e data da última sincronização;
- cache;
- armazenamento livre/total;
- playback;
- pareamento;
- versão atual, versão disponível e canal de update;
- último erro relevante.

Não é exibido o identificador interno. Não há timer de atualização: reabrir a tela ou uma mudança de playback visível atualiza o snapshot.

## Configuração inicial e pareamento

Na ausência de associação persistida, a tela mostra o código de seis dígitos e “Aguardando configuração…”. O código é somente uma referência de interface e não substitui o `internalId`, nem é tratado como globalmente único.

`DeviceAssignment` reserva campos para estabelecimento, nome da tela, cidade, localização lógica, playlist e configurações. Nenhum deles é preenchido remotamente nesta fase. O backend futuro deverá reservar e validar a associação usando a identidade interna.

O snapshot de pareamento é gravado de forma síncrona antes de ser publicado em memória, permitindo recuperação após encerramento do processo ou reboot.

## Health e heartbeat

As métricas Android usam `ActivityManager.MemoryInfo`, `StatFs` e `SystemClock.elapsedRealtime()` somente sob demanda. O health inclui uptime, memória disponível, armazenamento, conexão, versão, playback, cache, sync e último erro.

`LocalHeartbeatSource` cria o contrato pedido, mas não transmite nem agenda nada. O heartbeat usa o código amigável para apresentação; uma futura requisição deverá transportar também uma identidade autenticada fora desse contrato visual.

## Comandos

Tipos reservados: `RELOAD_PLAYLIST`, `SYNC_NOW`, `RESTART_PLAYER`, `CLEAR_CACHE`, `CHECK_UPDATE`, `REBOOT_DEVICE`, `CAPTURE_SCREENSHOT` e `GET_STATUS`.

Não existe transporte ou execução nesta fase. Comandos conhecidos retornam `Deferred`; valores desconhecidos são normalizados para `UNKNOWN` e retornam `Unsupported`. Operações destrutivas como limpar cache ou reiniciar o dispositivo não foram implementadas.

## Update

Canal local `STABLE` por padrão, com suporte a `BETA`, persistido separadamente. Estados operacionais disponíveis:

`UP_TO_DATE`, `UPDATE_AVAILABLE`, `DOWNLOADING`, `DOWNLOADED`, `VALIDATING`, `INSTALLATION_UNAVAILABLE`, `INSTALLING`, `INSTALL_FAILED` e `INVALID`.

A validação/download continua pertencendo ao `PlayerUpdateManager` da Fase 4. A nova camada apenas expõe o estado ao diagnóstico e fornece o ponto de integração para logs. Não há source remoto concreto nem instalação automática, e a política anterior contra downgrade permanece inalterada.

## Recuperação e persistência

- identidade e configuração essencial: `EssentialConfigStore`;
- pareamento e assignment: `loopin_pairing`;
- canal de update: `loopin_update_settings`;
- último sync e último erro: `loopin_operational_state`;
- playlist/cache: publicação transacional ACTIVE/PREVIOUS da Fase 3.2;
- downloads: arquivos `.part` nunca são reproduzíveis;
- falhas de sync/update não substituem conteúdo ativo;
- reboot continua usando `BootReceiver` e o agendamento conservador já existente.

Force-stop imposto pelo Android impede qualquer aplicativo de se reiniciar sozinho até nova inicialização explícita do pacote. Depois dessa inicialização, toda a configuração persistida é recarregada. Nenhuma tentativa foi feita de contornar essa política.

## Logs

Foram definidos eventos operacionais de baixa frequência: `DEVICE_STARTED`, `DEVICE_READY`, `DEVICE_OFFLINE`, `DEVICE_ONLINE`, eventos de pairing, sync, update, cache e playback. Startup, transições de rede, eventos de sync e erro de playback estão conectados ao logger limitado existente. Contratos ainda não executados não geram logs artificiais.

## Performance

Nenhuma biblioteca foi adicionada. Não há serviço, thread, websocket, timer ou polling novo. Métricas e heartbeat são produzidos sob demanda. O agendamento de sync continua sendo um job one-shot com backoff.

## Limitações

- pareamento, heartbeat, comandos e update remoto não possuem backend;
- a atualização operacional ainda não recebe metadata real;
- o diagnóstico depende de o controle/teclado da TV Box fornecer uma das teclas técnicas;
- recuperação após force-stop requer que Android/usuário/ADB volte a iniciar o pacote;
- comportamento de boot, teclas, memória e armazenamento físico: **PENDENTE — VALIDAÇÃO EM MXQ**.

## Validação executada

Em 19/08/2026:

- 93 testes JVM passaram, incluindo os 18 cenários operacionais novos;
- Android Lint passou;
- `assembleDebug` e `assembleRelease` passaram;
- APK debug `2.0.0-phase5` instalado no LDPlayer;
- diagnóstico aberto por F1 exibiu ONLINE, cache OK, playback PLAYING, pairing UNPAIRED, canal STABLE, armazenamento e ausência de erro;
- após force-stop, o mesmo `internalId` e código `267019` foram recarregados;
- durante 20 segundos sem rede, o processo preservou o mesmo PID (`15537`) e não houve crash, ANR ou OOM;
- o logger persistente registrou `DEVICE_OFFLINE` e `DEVICE_ONLINE`;
- a conectividade do LDPlayer foi restaurada ao final.
