# Loopin Admin 2.0 mínimo

Frontend isolado criado para autenticar operadores, gerenciar telas e concluir o pareamento seguro do Loopin Player 2.0. Ele não reutiliza nem altera o Admin antigo.

## Configuração local

Copie `.env.example` para `.env.local` e informe somente as credenciais públicas do projeto Supabase do Player 2:

```text
VITE_SUPABASE_URL=https://zdhsfirabkmivuzwyids.supabase.co
VITE_SUPABASE_ANON_KEY=<chave pública anon>
```

Nunca use `SUPABASE_SERVICE_ROLE_KEY` no frontend.

## Comandos

```powershell
npm.cmd install
npm.cmd test
npm.cmd run build
npm.cmd run dev -- --port 4173
```

## Funcionalidades desta versão

- login Supabase Auth por email/senha;
- sessão persistente com renovação automática;
- logout;
- listagem e criação de telas protegidas por RLS;
- indicação de Player vinculado;
- vínculo por código numérico de seis dígitos;
- leitura de QR pela câmera com ZXing;
- validação estrita de `loopin://pair?v=1&type=loopin-device-pairing&token=...`;
- confirmação visual antes do vínculo;
- mensagens de erro amigáveis sem detalhes internos;
- token QR mantido somente em memória e descartado no cancelamento, erro ou sucesso.

O Admin 2.0 não possui playlist, campanhas, upload, clima, OTA ou dashboard operacional nesta fase.
