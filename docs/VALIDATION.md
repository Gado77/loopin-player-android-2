# Matriz de validação da fundação

## Automatizada

- identidade interna determinística a partir de uma identidade estável do aparelho;
- formato, estabilidade e validação do código amigável de seis dígitos;
- estado `READY_OFFLINE` como condição válida;
- compilação debug e release;
- lint Android;
- testes unitários de todos os módulos.
- ordenação determinística, avanço, loop e playlist vazia;
- item inválido, retry, salto após erro e falha de todos os itens;
- duração obrigatória de imagem e transições da máquina de estados;
- liberação do adaptador de item.

## Em TV Box real

1. Instalar sem substituir `com.loopin.loopintv`.
2. Abrir sem rede e confirmar que a identidade aparece imediatamente.
3. Reiniciar três vezes e confirmar que a identidade interna e o código amigável não mudam.
4. Reinstalar no mesmo aparelho e verificar se o firmware preserva o `ANDROID_ID`; quando preservado, confirmar a recuperação do mesmo código.
5. Definir `Loopin Player 2.0` como launcher HOME e reiniciar.
6. Confirmar boot normal e quick boot do firmware MXQ usado em produção.
7. Remover e restaurar Ethernet/Wi-Fi sem reiniciar a Activity.
8. Pressionar Back, Home, Recents e teclas do controle.
9. Colocar o app em background e retornar, verificando immersive mode.
10. Forçar encerramento e confirmar que o launcher do sistema o restaura conforme a política do firmware.
11. Provisionar Device Owner e validar lock task permitido.
12. Inspecionar `dumpsys meminfo com.loopin.player2` por 24 horas.
13. Verificar rotação dos logs após ultrapassar 512 KiB.
14. Sem rede, observar vídeo → imagem por 3 segundos → vídeo e confirmar ao menos dez loops.
15. Substituir temporariamente um URI local por arquivo inválido e confirmar retry, salto e continuidade.
16. Executar ciclos Home/retorno e inspecionar `dumpsys media.codec` para confirmar a liberação do decoder.

## Limites desta etapa

- Não existe watchdog externo de frame/player nesta fase.
- Boot launch pode ser bloqueado pelo Android/OEM até o app ser escolhido como HOME.
- Forçar parada manual pelo sistema impede receivers até nova abertura, regra da plataforma.
- Telemetria e comandos remotos não fazem I/O.
- Compatibilidade de codec depende da implementação MediaCodec do firmware; o teste empacotado usa H.264 Baseline de baixa resolução.
- A reprodução instrumental em aparelho físico continua necessária para detectar defeitos específicos de firmware MXQ.
