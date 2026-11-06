import { useState } from 'react'
import { gmailLink, mailAppLink, whatsappLink, type Outgoing } from './sendLinks'
import { Button } from './ui'
import { WhatsAppIcon } from '../shared/ContactIcons'

/** Send on WhatsApp · Send email (Gmail) · Copy message · Done, with the mail-app fallback link. */
export function SendButtons({ outgoing, onDone }: { outgoing: Outgoing; onDone: () => void }) {
  const [copied, setCopied] = useState(false)
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(outgoing.message)
      setCopied(true)
    } catch {
      setCopied(false)
    }
  }
  return (
    <>
      <div className="mt-3 flex flex-wrap gap-2">
        <a href={whatsappLink(outgoing)} target="_blank" rel="noopener noreferrer"
          className="inline-flex items-center gap-2 rounded-lg bg-[#25d366] px-4 py-2 text-sm font-medium text-white hover:bg-[#1ebe5b]">
          <WhatsAppIcon /> Send on WhatsApp
        </a>
        <a href={gmailLink(outgoing)} target="_blank" rel="noopener noreferrer"
          className="inline-flex items-center gap-2 rounded-lg border border-line bg-white px-4 py-2 text-sm font-medium hover:bg-stone-50">
          <MailIcon /> Send email
        </a>
        <Button onClick={copy}>{copied ? 'Copied ✓' : 'Copy message'}</Button>
        <Button variant="ghost" onClick={onDone}>Done</Button>
      </div>
      <p className="mt-2 text-xs text-muted">
        {!outgoing.phone && 'WhatsApp will ask which chat to send it to. '}
        Send email opens Gmail; using Outlook or another mail app? <a href={mailAppLink(outgoing)} className="underline underline-offset-2 hover:text-ink">Open in mail app</a>
      </p>
    </>
  )
}

function MailIcon() {
  return (
    <svg viewBox="0 0 24 24" className="size-4" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
      <rect x="3" y="5" width="18" height="14" rx="2" />
      <path d="m3.5 6.5 8.5 6.5 8.5-6.5" />
    </svg>
  )
}
