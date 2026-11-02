# Overview

McreatiK Live Gallery puts wedding photos on guests' phones within seconds of the shot. Studios get a dashboard to run events. Couples get a private portal for album picks and original downloads.

> **Note** This documentation was written from a read-only review of the `main` branch. Every statement points to a real file or function. Anything that could not be confirmed from the code is marked **(assumption)**. Secret values are never shown; settings are named, not printed.

## What the product does

| Who | What they do | Where |
| --- | --- | --- |
| Guests | Scan a QR code, watch photos arrive live, download and share | `/e/{slug}` |
| Studios | Create events, connect cameras, manage photos, give the couple a login | `/admin` |
| McreatiK | Everything a studio can, plus studios, plans, payments and gallery switches | `/admin` (super admin) |
| Couples | Pick album photos, download originals and ZIPs | `/client` |
| Camera operator | Runs the uploader app on a laptop at the venue | Desktop app |

## The three apps

| App | Folder | Built with | Runs on |
| --- | --- | --- | --- |
| Web app | `web/` | React 19, TypeScript, Vite, Tailwind CSS 4, react-router | Cloudflare Workers static assets: live.mcreatik.com |
| Backend API | `backend/` | Spring Boot 4.1, Java 21, JPA + JdbcTemplate, Flyway | Docker on the Oracle VM: im-api.mcreatik.com |
| Uploader | `uploader/` | Java Swing, SQLite queue, built-in FTP server | The photographer's laptop |

They share one PostgreSQL database (Supabase) and two Cloudflare R2 buckets. Originals sit in a private bucket. Gallery copies sit in a public bucket served at im-media.mcreatik.com.

## How to read these docs

1. **Architecture** shows how the parts connect.
2. **Frontend**, **Backend**, **Database** and **API reference** explain the code.
3. **User journeys** follow each person through the system.
4. **Security and performance** and **Deployment** cover running it.
5. **Findings and next actions** lists every issue found, ranked.
6. **Glossary** explains the technical words.

## Repository map

```text
mcreatik-instant-memories/
├── backend/          Spring Boot API, Flyway migrations, these docs (src/main/resources/docs)
├── web/              React app: guest gallery, studio dashboard, couple portal
├── uploader/         Desktop uploader for the venue laptop
├── deploy/oracle/    deploy.sh, rollback.sh, nginx and Caddy configs, env example
└── .github/          CI, backend image build, Dependabot
```
