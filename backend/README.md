# SUM X backend

## Render (or any Node host)
* Build command: `npm install && npm run build`
* Start command: `npm start`  (applies db/schema.sql automatically, then starts the API)
* Environment: `DATABASE_URL`, `JWT_SECRET` (long random), `GOOGLE_WEB_CLIENT_ID`,
  and for email codes one of `RESEND_API_KEY` / `BREVO_API_KEY` (+ `MAIL_FROM`). Render's free plan blocks SMTP.
* Check `https://YOUR-SERVICE.onrender.com/health` -> `{"ok":true,"db":true,"mail":"..."}`.
  `db:false` means DATABASE_URL is wrong; `mail:"none"` means new accounts are auto-verified.
* Render free: the service sleeps when idle (first request ~1 min) and local disk (uploads) is wiped on restart.
