# Architecture (V1 design, superseded)

This file was the V1 design written in October 2026. It described an earlier hosting setup and a product without payments, packs or a Client gallery. None of that is current, so the old text has been removed.

The maintained architecture is in:

- [`backend/src/main/resources/docs/architecture.md`](../backend/src/main/resources/docs/architecture.md)
- the in-app Docs page `/admin/docs/architecture` (McreatiK login)

The other reference pages sit beside it: `backend.md`, `frontend.md`, `database.md`, `api.md`, `security.md`, `deployment.md` and `journeys.md`.

## Current summary

- Photo files never pass through the backend. Uploads go straight to Cloudflare R2 through short-lived presigned links, and guests load photos from the CDN at im-media.mcreatik.com.
- The backend (Spring Boot 4, Java 21, Docker on the Oracle VM) signs links, enforces rules, keeps records in PostgreSQL (Supabase) and pushes live updates to guests with Server-Sent Events.
- The web app (React 19, Vite) runs on Cloudflare Workers at live.mcreatik.com.
- The uploader (Java Swing, SQLite queue, built-in FTP server) prepares 2048 px and 640 px JPEGs on the laptop and uploads only those.
- Each photo and category belongs to the `GUEST` gallery (compressed, shown to guests) or the `CLIENT` gallery (full quality, private to the couple).
- Payment is by UPI QR and UTR, confirmed by McreatiK. There is no payment gateway.
