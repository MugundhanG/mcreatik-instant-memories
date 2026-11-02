# Findings and next actions

Fix the two High items first. Each takes minutes. Everything below Medium is maintenance, to do when convenient.

## Do now

1. **Check which proxy is live.** Run `systemctl is-active nginx caddy` on the VM. If nginx is active, change its `X-Forwarded-For` line to `proxy_set_header X-Forwarded-For $remote_addr;` and reload.
2. **Don't use `rollback.sh`** until its one-line fix is merged. Roll back by re-running `deploy.sh` with an older `:sha-` image.
3. **Fix the security one-pager:** it says "checked weekly", but Dependabot runs monthly.

## Do next

1. Add a free uptime monitor for `https://im-api.mcreatik.com/actuator/health` and `https://live.mcreatik.com`.
2. Confirm the Supabase backup level, and decide whether originals need a second copy.
3. Load-test the guest gallery before the biggest wedding on the calendar.
4. Make two rules habits: deploy only when CI is green, and never deploy during an event.

## All findings

| Severity | Area | Finding | Evidence |
| --- | --- | --- | --- |
| High | Security | Login rate limit bypassable with a fake `X-Forwarded-For` when nginx is the proxy | `application.yml`, `nginx-im-api.conf`, spring-web `ForwardedHeaderUtils` |
| High | Deployment | `rollback.sh` removes the live container, then fails to start the old one | Dry run: `"docker run" requires at least 1 argument` |
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
