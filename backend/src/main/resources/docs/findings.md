# Findings and next actions

No High items remain: Caddy is the live proxy, so the `X-Forwarded-For` finding does not apply (confirmed 3 November 2026). Everything below Medium is maintenance, to do when convenient.

## Do now

1. **Fix the security one-pager:** it says "checked weekly", but Dependabot runs monthly.

## Do next

1. Add a free uptime monitor for `https://im-api.mcreatik.com/actuator/health` and `https://live.mcreatik.com`.
2. Confirm the Supabase backup level, and decide whether originals need a second copy.
3. Load-test the guest gallery before the biggest wedding on the calendar.
4. Make two rules habits: deploy only when CI is green, and never deploy during an event.

## All findings

| Severity | Area | Finding | Evidence |
| --- | --- | --- | --- |
| Resolved | Security | Login rate limit bypassable with a fake `X-Forwarded-For` when nginx is the proxy. Caddy is live (`via: 1.1 Caddy`, 3 November 2026), so it does not apply | `application.yml`, `nginx-im-api.conf`, spring-web `ForwardedHeaderUtils` |
| Medium | Deployment | Every deploy is an outage during Java startup | `deploy.sh` |
| Medium | Deployment | Backend image is built even when CI fails | `backend-image.yml` |
| Medium | Operations | No monitoring or alerts | Repo |
| Medium | Operations | No defined backups | Repo |
| Medium | Scale | Single server; rate limits, SSE and ZIP jobs in memory | `PublicRateLimitFilter`, `SseGalleryBroadcaster`, `ZipDownloads` |
| Medium | Security | Login tokens in localStorage for 12 h | `admin/auth.ts`, `client/clientApi.ts` |
| Medium | Privacy | Gallery photo links work for anyone who holds them | Public media bucket (by design) |
| Low | Product | Auto-LIVE doesn't update guests who opened early | `UploadService.complete` → `promoteToLive` |
| Low | Product | Dashboard uploads don't trigger auto-LIVE | `UploadService.adminUpload` |
| Low | Data | No unique index for one client per event; client email unique case-sensitively | `V8__categories_clients_album.sql` |
| Low | Data | No `CHECK` constraints on role, plan or payment status | Migrations |
| Low | Data | Event delete removes files before the database row | `EventService.delete` |
| Low | Security | Some requests lack `@Valid` limits; no per-account lockout | `ClientController`, `AlbumDtos`, `BillingController` |
| Low | Security | Uploader token in plain text on the laptop; LAN FTP unencrypted | `UploaderConfig.save`, `FtpPhotoSource` |
| Low | Trust | One-pager claims weekly dependency checks; config is monthly | `.github/dependabot.yml` |
| Low | Code | No error boundary; hand-kept types; no OpenAPI; large `PhotosPanel` | `web/src` |
| Low | Code | Leftovers: `web/vercel.json`, two proxy configs, `wget` healthcheck | Repo |
| Fixed | Deployment | `rollback.sh` used to remove the live container, then fail to start the old one; it now starts the previous image with the deploy's memory and CPU settings and waits for the health check | `deploy/oracle/rollback.sh` |
