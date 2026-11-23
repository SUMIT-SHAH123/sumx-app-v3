# SUM X — free Discord-style communication app (Android + Node backend)

## Run the backend
1. `cd backend && docker compose up -d`      (PostgreSQL)
2. `cp .env.example .env` and set JWT_SECRET (+ optional Google, SMTP, TENOR_API_KEY, TURN_*)
3. `npm install && npm run migrate && npm run dev`   (re-run `migrate` after every update; it is idempotent)
Without SMTP_HOST, verification/reset codes print in the server console.

## Run the Android app
Open `android/` in Android Studio, copy `local.properties.example` values into `local.properties`
(API_BASE_URL, GOOGLE_WEB_CLIENT_ID). Run on a real device for calls/camera (emulators have limited WebRTC support).

## Google Sign-In
Google Cloud Console > Credentials: create a **Web** OAuth client (its ID goes in backend `.env` and `local.properties`)
and an **Android** OAuth client (package `com.sumx.app` + your SHA-1 from `./gradlew signingReport`).

## Features
Auth (email + Google, verification, reset, rotating sessions, per-device logout) · unique SUM X IDs · username change (14-day limit)
· friends (requests, block, mutual friends) · presence (online/idle/DND/invisible) · real-time DMs (edit, delete, reply, reactions, emoji,
GIFs, images, files, voice messages, pins, search, typing, read receipts, mute, delete conversation)
· profiles (avatar, banner, background, colors, themes, frames, decorations, effects, name styles, badges, live preview)
· activity + listening activity with per-field privacy · in-app notifications + settings · 1:1 voice/video calls (WebRTC) + call history
· settings pages · account deletion.

## Honest limitations
* Calls only ring while the app process is alive (closed-app ringing needs FCM + a foreground service) and screen sharing is not implemented.
  Add a TURN server (coturn, TURN_URLS/TURN_SECRET) for users behind strict NATs.
* Music detection uses Android MediaSession via Notification access; it only sees apps that publish a media session. No Spotify/YouTube OAuth.
* GIF search needs a Tenor API key. File storage is local disk (UPLOAD_DIR); swap for S3-compatible storage in production.
* Authorization is enforced in the API layer (every query is scoped to the caller), not with Postgres row-level security.
* Profile "share link" shares the SUM X ID text; web deep links need a domain.
* Nothing here has been compiled or load-tested in this environment — expect to fix a few build errors on first sync.
