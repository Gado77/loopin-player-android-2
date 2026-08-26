# Relatório de validação no LDPlayer

Data: 18/08/2026

## Resultado

O Loopin Player 2.0 foi instalado, iniciado e exercitado com sucesso no LDPlayer via ADB. O laboratório permite instalar/atualizar, iniciar/parar, verificar processo, coletar logcat, memória, CPU e dumpsys e gerar relatórios sem interação visual manual.

## APK validado

- Caminho: `app\build\outputs\apk\debug\app-debug.apk`
- Pacote: `com.loopin.player2`
- Versão: `2.0.0-phase2` (`versionCode` 1)
- SDK mínimo/alvo: 21/36
- Tamanho no momento da instalação: 5.143.242 bytes
- Resultado da instalação: `Success`; atualização preservando dados locais.

## Fundação e identidade

- A MainActivity entrou em estado `RESUMED`.
- Primeira abertura observada: 3.613 ms; abertura após force-stop: 1.215 ms.
- Identidade interna persistente: `2e2b1a1b-cda3-49dd-82c6-6e15ae7698e4`.
- Código amigável persistente exibido: `267019`.
- Ambos permaneceram iguais após force-stop, reinício do aplicativo e reinício do emulador.
- O identificador legado `TELA-5ACED0DB` continua apenas no armazenamento de migração e não é exibido pela interface.

## Reprodução local e loop

- A sequência local vídeo → imagem → vídeo avançou continuamente.
- Estados capturados incluem `PLAYING • image-a • loop 1`, `PLAYING • video-b • loop 1` e `PLAYING • image-a • loop 5`.
- Em uma observação de 60 segundos, o contador avançou do loop 8 ao loop 16 com o mesmo processo.
- O Media3/ExoPlayer 1.8.1 selecionou `OMX.google.h264.decoder` para H.264.
- Não houve FATAL EXCEPTION, ANR, OOM ou erro de codec.

## Recuperação e lifecycle

1. **Force-stop e reinício:** o PID antigo desapareceu, o Player iniciou com novo PID e retomou a sequência local mantendo as identidades.
2. **Background e retorno:** o processo permaneceu ativo; ao retornar, a reprodução continuou. O comando HOME abriu o seletor do Android porque nenhum launcher padrão foi escolhido.
3. **Execução continuada:** o mesmo PID permaneceu ativo por 60 segundos e os loops progrediram.

Memória PSS antes/depois da observação: 49.610 kB → 50.337 kB (+727 kB). Havia uma activity e 53 views; WebViews: zero. A amostra de CPU ao final foi 0%. Não apareceu tendência de crescimento relevante nessa janela curta.

## Funcionamento offline

O teste seguro foi realizado pelo controle de rede do LDPlayer: rede em `offline`, espera de 30 segundos e restauração para `connect`. Após a restauração do ADB, o Player permanecia funcional e a reprodução local havia avançado do loop 1 ao loop 11. A rede foi restaurada ao fim do teste.

Uma tentativa inicial de desligar o Wi-Fi via `svc wifi disable` também derrubou o canal ADB virtual. A instância precisou ser reiniciada, sem perda das identidades persistidas. Esse método não deve ser usado neste laboratório.

## Logs e observações

- Artefatos: `diagnostics\ldplayer\final-20260818`.
- Captura visual: `diagnostics\ldplayer\final-20260818\screen.png`.
- O log contém o aviso não fatal `Ignoring messages sent after release` nas transições após liberar um player. Deve ser acompanhado em ensaios longos, mas não interrompeu a reprodução.
- Há negações SELinux relativas a `/dev/fastpipe` com o LDPlayer em modo permissivo; são específicas do emulador e não causaram falha do aplicativo.

## Conclusão e próximo passo

O laboratório LDPlayer está operacional para regressões automatizadas da fundação e da reprodução local. O próximo passo recomendado, sem avançar funcionalmente a Fase 3, é repetir um soak test de várias horas e depois reproduzir a mesma matriz na MXQ real quando o ADB físico estiver disponível.
