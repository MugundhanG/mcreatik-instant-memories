import type { ReactNode } from 'react'
import { PRIVACY_EMAIL } from './privacy'
import { SiteFooter } from './SiteFooter'

const UPDATED = '14 November 2026'

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="mt-8">
      <h2 className="text-lg font-semibold text-ink">{title}</h2>
      <div className="mt-2 space-y-2 text-[15px] leading-relaxed text-ink/85">{children}</div>
    </section>
  )
}

/** Plain-language privacy notice for guests, couples and studios. Every statement here must stay true. */
export default function PrivacyPage() {
  return (
    <div className="min-h-dvh bg-paper">
      <main className="mx-auto max-w-2xl px-5 py-12">
        <p className="text-xs font-semibold tracking-[0.3em] text-[#8a5a1c] uppercase">McreatiK Instant Memories</p>
        <h1 className="mt-2 font-display text-4xl font-medium text-ink">Privacy &amp; your photos</h1>
        <p className="mt-2 text-sm text-muted">Last updated {UPDATED}</p>

        <Section title="Who we are">
          <p>
            McreatiK Instant Memories shares event photos from photography studios with their clients and guests. The studio that
            photographed the event decides which photos are shared and for how long; McreatiK runs the gallery for them.
          </p>
        </Section>

        <Section title="Guests: what we collect">
          <p>You don't sign up, and we never ask for your name, phone number or email. Unless you choose the optional face search below, we collect nothing that identifies you.</p>
          <p>
            To show the studio how many people viewed and saved photos, your browser keeps a random number. We store that
            number with what was viewed or saved, never your name, contacts or location.
          </p>
          <p>
            Like any website, our servers keep short-lived technical logs (which include IP addresses) to block abuse.
          </p>
        </Section>

        <Section title="Face search (optional)">
          <p>
            Some studios add <b>Find my photos</b> to an event. It is offered only at events where the studio has bought it, and it is
            your choice: nothing happens unless you tap the button, read the notice and agree.
          </p>
          <ul className="list-disc space-y-1.5 pl-5">
            <li>Your selfie is used once, for that search, to find the photos you are in. It is not saved, and we do not keep it or log it.</li>
            <li>To make the search work, the event's gallery photos are scanned and each face in them becomes a face template (a string of numbers, not a photo). These templates are processed by Amazon Web Services (AWS) in Mumbai, India, only to match photos at that event. They are not used to identify anyone anywhere else, and not shared for any other purpose.</li>
            <li>Face templates are deleted when a photo is removed, and with the rest of the gallery's photos 30 days after the gallery's open period ends (or sooner if the studio turns face search off or deletes the event).</li>
            <li>You can ask us to remove your face data at any time: email <a className="underline underline-offset-4" href={`mailto:${PRIVACY_EMAIL}`}>{PRIVACY_EMAIL}</a> with the gallery link.</li>
          </ul>
        </Section>

        <Section title="How photos are protected">
          <ul className="list-disc space-y-1.5 pl-5">
            <li>Location (GPS) and camera details are removed from every photo shown in the gallery. Only the full-quality originals the couple downloads keep the camera's details.</li>
            <li>A gallery can only be opened with its link or QR code. Addresses are hard to guess, and galleries are hidden from Google and other search engines.</li>
            <li>Full-quality originals are kept private. Only the couple and the studio can download them, after logging in, through private links that expire within 30 minutes.</li>
            <li>Everything travels over encrypted HTTPS connections. Photos are stored with Cloudflare and data with Supabase, which encrypt stored data.</li>
            <li>Passwords are stored only in scrambled (hashed) form. Changing a password logs out every other device.</li>
            <li>Each studio sees only its own events, and each couple sees only their own event.</li>
          </ul>
        </Section>

        <Section title="How long photos are kept">
          <p>
            Photos are deleted 30 days after the gallery's open period ends, unless the studio keeps it open longer. Deleting
            photos permanently removes them and their files from our storage. The event record itself (its name, date,
            studio and payments) is kept as a business record and evidence of the events a studio took. Copies held by our delivery network can stay reachable for a while,
            but only by someone who already has that exact photo's link.
          </p>
        </Section>

        <Section title="Want a photo removed?">
          <p>
            Open the photo in the gallery and tap <b>Request removal</b>, or email{' '}
            <a className="underline underline-offset-4" href={`mailto:${PRIVACY_EMAIL}`}>{PRIVACY_EMAIL}</a> with the gallery link.
            We pass every request to the studio and aim to take the photo down within 3 working days.
          </p>
        </Section>

        <Section title="Questions or complaints">
          <p>
            Email <a className="underline underline-offset-4" href={`mailto:${PRIVACY_EMAIL}`}>{PRIVACY_EMAIL}</a>. You can also
            contact the studio that photographed your event.
          </p>
        </Section>
        <SiteFooter className="mt-12 border-t border-line pt-6" />
      </main>
    </div>
  )
}
