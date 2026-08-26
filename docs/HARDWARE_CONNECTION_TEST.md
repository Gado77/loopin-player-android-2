# Teste de conexão física — MXQ

Data: 18/08/2026  
Host: Windows 10.0.26200

## Conclusão

**Não foi possível controlar ou diagnosticar a MXQ por USB/ADB na configuração atual.**

O ADB do computador funciona, porém nenhum dispositivo aparece em `adb devices -l`. Mais importante: o Windows não enumerou nenhum dispositivo USB que possa ser atribuído à MXQ. Também não há interface Android, ADB, dispositivo desconhecido ou dispositivo PnP com erro.

Nenhum comando foi enviado à TV Box, nenhum driver foi alterado e nenhum APK foi instalado.

### Reteste — 18/08/2026 14:19:53 -03:00

A enumeração do Windows e `adb devices -l` foram repetidos após nova solicitação. O resultado permaneceu inalterado: lista ADB vazia, nenhum novo VID/PID, nenhuma interface Android/ADB, nenhum estado `unauthorized`/`offline` e nenhum dispositivo PnP com erro. Permanecem visíveis somente `0408:403A`, `04CA:3802` e `1D57:FA60`, já identificados como periféricos do computador.

## ADB no computador

- Executável: `C:\Users\itach\.cache\loopin-player2-toolchain\android-sdk\platform-tools\adb.exe`
- Android Debug Bridge: `1.0.41`
- Platform Tools: `37.0.1-15733141`
- Servidor local iniciado normalmente em `tcp:5037`.
- Resultado de `adb devices -l`: lista vazia.
- Estado `device`: não encontrado.
- Estado `unauthorized`: não encontrado.
- Estado `offline`: não encontrado.

## USB detectado pelo Windows

Não surgiu um VID/PID atribuível à TV Box. Os dispositivos USB presentes são periféricos identificados do próprio computador:

| VID:PID | Identificação do barramento | Driver | Relação com a MXQ |
|---|---|---|---|
| `0408:403A` | `ACER HD User Facing` / `APP Mode` | `usb.inf`, `winusb.inf`, driver SunplusIT | Câmera interna Acer |
| `04CA:3802` | `Wireless_Device` / MediaTek Bluetooth MT7921 | `usb.inf` | Bluetooth interno |
| `1D57:FA60` | `2.4G Wireless Device` | `usb.inf`, `input.inf`, `HidUsb` | Receptor HID 2,4 GHz |

Todos estavam registrados antes deste teste. Não existem dispositivos presentes com `ConfigManagerErrorCode` diferente de zero.

### Interface ADB

- `Android USB Device`: não encontrada.
- `Android ADB Interface`: não encontrada.
- `USB debugging interface`: não encontrada.
- Driver ADB: não associado a nenhum dispositivo.
- Dispositivo desconhecido potencialmente correspondente à MXQ: não encontrado.

## Diagnóstico provável

A ausência total de enumeração indica que o bloqueio ocorre antes do driver ADB. As causas possíveis, em ordem prática de verificação, são:

1. a porta USB usada na MXQ funciona somente como **host**, não como OTG/device;
2. a TV Box não está energizada pela fonte própria ou não inicializou;
3. o cabo/porta não estabelece um enlace USB de dados compatível;
4. o firmware não expõe função USB device/gadget nessa porta;
5. depois que a enumeração física funcionar, a depuração USB ainda estará desabilitada;
6. somente após existir uma interface USB Android poderá ser necessário instalar/associar um driver ADB no Windows.

Apenas a ausência de depuração USB normalmente não explica, por si só, a inexistência de qualquer enumeração USB; por isso porta/modo físico/cabo/energia devem ser comprovados primeiro.

## APK do Player 2.0

- Package/applicationId: `com.loopin.player2`
- Versão: `2.0.0-phase2` (`versionCode 1`)
- `minSdk`: 21
- `targetSdk`: 36
- Caminho: `C:\Users\itach\OneDrive\Documentos\ChatGPT\LoopinPlayer2\app\build\outputs\apk\debug\app-debug.apk`
- Tamanho: 5.143.242 bytes
- SHA-256: `82844450B30C1E9CCA42521ABF6E5C691DE890D7AADAE1CE69EABAE704430E41`
- Instalação: **não realizada**, pois não existe dispositivo ADB acessível.

## Informações e logs da TV Box

Não foi possível coletar fabricante, modelo, Android, API, arquitetura, resolução, memória, MediaCodec, pacotes, processos, `logcat` ou `dumpsys`, porque não existe transporte ADB conectado.

## Automação

Os scripts de instalação, início, parada, coleta e relatório não foram criados nesta execução. A especificação condiciona essa preparação a uma conexão ADB funcional; criar automação agora não provaria comunicação com o hardware.

## Próximo passo físico recomendado

Quando houver tela/controle disponíveis:

1. alimentar a MXQ pela fonte correta;
2. identificar no modelo/placa qual conector é realmente **OTG/device** — uma porta USB-A comum da box frequentemente é apenas host;
3. usar um cabo de dados apropriado para essa porta, evitando ligação host-host ou alimentação cruzada;
4. iniciar o Android e habilitar **Opções do desenvolvedor → Depuração USB**;
5. reconectar ao computador e aceitar na tela da MXQ a autorização da chave RSA, se solicitada;
6. repetir a enumeração do Windows e `adb devices -l`;
7. somente se aparecer `unauthorized`, aceitar a chave na TV Box; somente se aparecer `device`, prosseguir com diagnóstico e instalação.

Android 4.2.2 ou superior normalmente exige confirmação física da chave RSA no aparelho na primeira conexão. Não há uma forma legítima de contornar essa autorização pelo computador em um aparelho não previamente configurado.

## Limitações

- Não havia monitor/TV para confirmar alimentação, boot, porta selecionada ou diálogo RSA.
- Não foi possível comparar o barramento antes/depois de desconectar o cabo durante esta execução.
- O modelo exato e o SoC da MXQ são desconhecidos; diferentes revisões usam portas e firmwares distintos.
- Nenhuma conclusão definitiva sobre defeito do cabo pode ser tomada sem testar a porta OTG correta e observar uma nova enumeração.
