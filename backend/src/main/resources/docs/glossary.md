# Glossary

Plain-language meanings of the technical words used in these docs, with where each one shows up in McreatiK Live Gallery.

| Term | Meaning, and where it shows up |
| --- | --- |
| API | The set of web addresses the apps call to read or change data (`/api/...`) |
| BCrypt | Slow, one-way scrambling of passwords; the database never holds the real password |
| Bearer token | A string sent in the `Authorization` header that proves who you are |
| CDN | Servers near the viewer that deliver files quickly; im-media serves gallery photos this way |
| CI/CD | Automatic testing (CI) and building and releasing (CD), here on GitHub Actions |
| Component | A reusable piece of React screen, such as `PhotoViewer` |
| CORS | A browser rule naming which websites may call the API (`CORS_ALLOWED_ORIGINS`) |
| CSP | Content Security Policy: a header listing where scripts, images and connections may come from |
| Docker image and container | A packaged app (image) and a running copy of it (container) |
| DTO or record | A small Java data class that becomes JSON, such as `EventDetail` |
| EXIF | Hidden camera data in a photo, including GPS; removed from gallery copies |
| Flyway migration | A numbered SQL file (`V1__` to `V25__`) that changes the database in order |
| Guest gallery and Client gallery | The two photo sets in an event's Photos tab. Guest gallery: camera photos and compressed uploads, seen by guests. Client gallery: full-quality finals the studio uploads, seen by the couple. Stored as `photos.gallery` = `GUEST` or `CLIENT` |
| GHCR | GitHub Container Registry, where backend images are stored |
| HD request | A guest taps "Get HD photo" and leaves an email or WhatsApp number. Shown on the studio's Guests tab (`hq_requests` table) |
| Hook | A React function that adds behaviour to a component, such as `useLiveGallery` |
| Idempotent | Safe to repeat: calling `complete` twice gives the same result |
| Index | A database lookup structure that makes a query fast |
| JPA and Hibernate | Java tools that map table rows to objects such as `Photo` |
| JWT | A signed token holding who you are, your scope and `ver`, checked on every request |
| Keyset (cursor) paging | Paging by "items older than this one" instead of page numbers, so new arrivals don't shift pages |
| Lazy loading | Downloading part of the app only when it is opened |
| Mermaid | The text format used to draw the diagrams in these docs |
| Presigned URL | A short-lived signed link allowing one upload or download to R2 without the server in the middle |
| R2 | Cloudflare's file storage: one private bucket for originals, one public bucket for gallery copies |
| Rate limit (token bucket) | A cap on requests per IP that refills over time; an empty bucket returns 429 |
| Reducer | A function that takes the current state and an action and returns the new state |
| Reverse proxy | nginx or Caddy in front of the backend, handling HTTPS for im-api |
| Slug | The random 10-character part of a gallery address, `/e/{slug}` |
| SSE (Server-Sent Events) | One open connection over which the server pushes new photos to the phone |
| `SKIP LOCKED` | A Postgres option that lets several workers take different queue rows without waiting |
| Super admin | McreatiK's own role (`SUPER_ADMIN`), able to see and switch everything |
| `token_version` | A counter on each login; raising it makes every older token invalid |
| Transaction | A group of database changes that all happen or none do |
| Virtual thread | A very cheap Java thread, so thousands of open connections cost little |
