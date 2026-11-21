# Deploy gratuito — Espelho Remoto

## Android APK
Abra **Actions** no GitHub, escolha **Android debug APK**, e execute **Run workflow**. O workflow usa Gradle e publica o arquivo `app-debug.apk` como artifact quando o build é aprovado. Se falhar, consulte os logs; nenhuma compilação foi confirmada.

## Servidor no Render
1. Acesse https://dashboard.render.com/ e conecte este repositório privado.
2. Crie um Blueprint a partir do `render.yaml`.
3. Confirme o serviço Node gratuito, com diretório raiz `server`.
4. Aguarde o deploy e teste `https://SEU-SERVICO.onrender.com/health` (resposta esperada: `{"status":"ok"}`).
5. Abra o app Android, informe a URL HTTPS, toque em iniciar e autorize o diálogo do sistema.
6. Abra o link gerado no navegador de outro aparelho.

## Limitações e segurança
O MVP exige consentimento do Android e notificação permanente de captura. O link de sessão funciona como segredo de acesso: não o divulgue. A hospedagem gratuita pode suspender o serviço e o STUN isolado não garante conectividade; produção requer autenticação e TURN. O campo de e-mail não envia mensagens. Não há APK nem deploy validados nesta documentação.
