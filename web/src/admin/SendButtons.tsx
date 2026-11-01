import { useState } from 'react'
import { gmailLink, mailAppLink, whatsappLink, type Outgoing } from './sendLinks'
import { Button } from './ui'

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

function WhatsAppIcon() {
  return (
    <svg viewBox="0 0 24 24" className="size-4" fill="currentColor" aria-hidden="true">
      <path d="M12 2a10 10 0 0 0-8.6 15.1L2 22l5-1.3A10 10 0 1 0 12 2Zm0 18.2a8.2 8.2 0 0 1-4.2-1.2l-.3-.2-3 .8.8-2.9-.2-.3A8.2 8.2 0 1 1 12 20.2Zm4.5-6.1c-.2-.1-1.5-.7-1.7-.8-.2-.1-.4-.1-.6.1l-.8 1c-.1.2-.3.2-.5.1a6.7 6.7 0 0 1-3.3-2.9c-.2-.4.2-.4.7-1.3.1-.2 0-.3 0-.4l-.8-1.8c-.2-.5-.4-.4-.6-.4h-.5a1 1 0 0 0-.7.3 3 3 0 0 0-.9 2.2 5.1 5.1 0 0 0 1.1 2.7 11.7 11.7 0 0 0 4.5 4c1.7.7 2.3.8 3.2.6.5-.1 1.5-.6 1.7-1.2.2-.6.2-1.1.2-1.2-.1-.1-.3-.2-.5-.3Z" />
    </svg>
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
