# Overview

McreatiK Instant Memories puts wedding photos on guests' phones within seconds of the shot. Studios get a dashboard to run events. Couples get a private portal for album picks and original downloads. Each event has two photo sets: a Guest gallery (compressed, for guests) and a Client gallery (full quality, for the couple); events made before 11 Nov 2026 keep one set.

> **Note** This documentation was written from a read-only review of the `main` branch. Every statement points to a real file or function. Anything that could not be confirmed from the code is marked **(assumption)**. Secret values are never shown; settings are named, not printed.

## What the product does

| Who | What they do | Where |
| --- | --- | --- |
| Guests | Scan a QR code, watch photos arrive live, download and share. With the optional **Find my photos** add-on they take a selfie and see the photos they are in; with **Private gallery** they see only those | `/e/{slug}` |
| Studios | Create events, connect cameras, manage photos, give the couple a login | `/admin` |
| McreatiK | Everything a studio can, plus studios, plans, payments and gallery switches | `/admin` (super admin) |
| Couples | Pick album photos, download originals and ZIPs | `/client` |
| Camera operator | Runs the uploader app on a laptop at the venue | Desktop app |

## Find my photos and Private gallery

**Find my photos** is an optional add-on per event (+₹699 per Main event, +₹399 per Quick event; not for Client-only events). The studio buys it in the event's **Settings → Face search** and switches it on and off there. A guest taps **Find my photos**, agrees to a short notice, takes one selfie with the front camera inside the page, and sees **Photos of you**. The phone remembers the guest, so new photos of them appear by themselves. Face data is processed by AWS in Mumbai and deleted with the photos.

**Private gallery** is a second switch in the same card (needs Find my photos on). Guests then see no photos until they take a selfie, and afterwards only the photos they are in. The couple's Client gallery is not affected. The event's **Analytics** page has a **Find my photos** section.

## The three apps

| App | Folder | Built with | Runs on |
| --- | --- | --- | --- |
| Web app | `web/` | React 19, TypeScript, Vite, Tailwind CSS 4, react-router | Cloudflare Workers static assets: live.mcreatik.com |
| Backend API | `backend/` | Spring Boot 4.1, Java 21, JPA + JdbcTemplate, Flyway | Docker on the Oracle VM: im-api.mcreatik.com |
| Uploader | `uploader/` | Java Swing, SQLite queue, built-in FTP server | The photographer's laptop |

They share one PostgreSQL database (Supabase) and two Cloudflare R2 buckets. Originals sit in a private bucket. Gallery copies sit in a public bucket served at im-media.mcreatik.com.

## How to read these docs

**Running the service day to day?** Start with **SOPs** (one per role: [McreatiK admin](/admin/docs/sop-admin), [Studios](/admin/docs/sop-studio), [Couples](/admin/docs/sop-couple), [Guests](/admin/docs/sop-guest)) and the **Camera & uploader guides** ([Uploader](/admin/docs/guide-uploader), [Canon](/admin/docs/guide-canon), [Sony](/admin/docs/guide-sony), [Event-day checklist](/admin/docs/guide-event-day), [Event-day problem playbook](/admin/docs/guide-event-day-problems)). The rest is the technical reference:

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
