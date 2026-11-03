# Security and performance

The audit found no critical issues, and no High issues remain. H1 does not apply because Caddy is the live proxy (confirmed 3 November 2026), and the broken rollback script has been fixed. Everything on this page was confirmed in code, deploy files or the Spring library itself, unless marked as an assumption.

## Protections in place

| Area | How it is protected | Evidence |
| --- | --- | --- |
| Deny by default | Unknown paths are refused | `SecurityConfig.publicChain` ends with `denyAll()` |
| Studios walled off | Ownership checked in every service; others' events return 404 | `EventService.owned`, `AdminPhotoController.owned` |
| Instant logout | `token_version` checked on every admin and couple request | `ActiveAdminFilter`, `ClientController.current` |
| Passwords | BCrypt, at least 10 characters, temporary passwords must be changed | `AccountController.MIN_PASSWORD`, `AlbumService` |
| No account discovery | Dummy hash on unknown emails; forgot password always 204 and emails in the background | `ClientController.login`, `PasswordResets` |
| Secret tokens | 256-bit `SecureRandom`; uploader tokens stored only as SHA-256 | `Tokens.randomToken`, `UploaderTokenAuthenticationFilter` |
| Private originals | Private bucket; links last 5 min (admin), 10 min (couple), 30 min (ZIP) | `AdminPhotoController.original`, `ClientController.download`, `ZipDownloads` |
| GPS removed | All three ways of making gallery copies re-encode without EXIF | `processing/ImageOps`, uploader `prepare/ImageOps`, `prepareUpload.ts` |
| Website headers | Strict CSP, HSTS, nosniff, no framing, Permissions-Policy | `web/public/_headers` |
| Container | Non-root user, bound to 127.0.0.1, memory and CPU capped, exits on out-of-memory | `backend/Dockerfile`, `deploy.sh` |
| Image bombs | Subsampled decoding, 60 MB upload cap, 1 MB logo cap with type check | `ImageOps`, `StudioBranding` |
| Dev endpoints | Local storage controller exists only with `STORAGE_TYPE=local` | `@ConditionalOnProperty` |
| This documentation | Served by the backend to the super admin only; not in the public website files | `DocsController` |

## Security findings

| ID | Severity | Finding | Fix |
| --- | --- | --- | --- |
| H1 | Resolved | With nginx in front, the login rate limit could be bypassed by sending a fake `X-Forwarded-For` (Spring takes the **leftmost** value and `nginx-im-api.conf` appends to the client's header). Caddy is the live proxy (confirmed 3 November 2026 from the `via: 1.1 Caddy` response header), and Caddy ignores a client-sent `X-Forwarded-For`, so the bypass does not apply. | None needed. If nginx is ever put in front, use `proxy_set_header X-Forwarded-For $remote_addr;` |
| M1 | Medium | Login tokens sit in localStorage for 12 hours | Mitigated by the strict CSP; consider shorter tokens later |
| M2 | Medium | One server with in-memory state (rate limits, SSE, ZIP jobs) | Accept for now; never deploy during events |
| M3 | Medium | 1 CPU / 900 MB is tight when originals, ZIPs and logins peak together | Most photos are prepared on the laptop; load-test before big events |
| M4 | Fixed | No defined backups (Supabase free plan keeps none) | Nightly `backup.sh` to the private bucket; photos rely on R2 durability plus the studio's own copies |
| M5 | Medium | Gallery photo URLs work for anyone holding the exact link | By design; keys are random UUIDs; disclosed on the privacy page |
| L1 | Low | Some couple, album and billing requests lack `@Valid` limits; no per-account lockout | Later hardening |
| L2 | Low | Uploader token stored in plain text on the laptop; camera-to-laptop FTP unencrypted (local Wi-Fi only) | Rotate the token if a laptop is lost |
| L3 | Low | ZIP links and guest analytics are bearer-style | Short-lived and rate limited |
| L4 | Low | The security one-pager says dependencies are checked weekly, but Dependabot runs monthly | Reword the one-pager |

## Performance

**Strong by design:**

- Photo bytes never touch the VM.
- Keyset paging and partial indexes.
- `SKIP LOCKED` queue.
- Virtual threads for SSE and ZIPs.
- Analytics batched every 4 s.
- Lazy JavaScript chunks; hashed assets cached for a year.
- gzip JSON.

**Watch points:**

| Item | When it matters |
| --- | --- |
| `ActiveAdminFilter` does one database lookup per dashboard call | Many studios polling at once |
| Dashboard stats and analytics polling | Each open dashboard runs aggregate queries |
| Offset paging in the admin photo list | Events with thousands of photos scrolled deep |
| Database pool of 10 | A busy wedding with uploads, dashboard and ZIP together |
| `guest_activity` grows with every view | Supabase storage limits |

> **Warning** No load test exists. How many guests one CPU can serve at once is unknown until it is measured.
