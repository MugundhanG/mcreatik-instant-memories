import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { Link, useLocation, useParams } from 'react-router'
import { adminApi, ApiError } from '../../lib/api'
import type { Brand, DocPage, DocPageSummary } from '../../lib/types'
import mkLogo from '../../assets/mk-logo.webp'
import { ShutterLoader } from '../../shared/ShutterLoader'
import { useSession } from '../auth'
import { slugify } from './docsText'
import { Markdown } from './Markdown'

/** Guides written for the studio's clients: shown and printed under the studio's own name and logo. */
const CLIENT_GUIDES = new Set(['sop-couple', 'sop-guest', 'qa-couples', 'qa-guests'])

/** A4 with a margin while the docs are open (the QR poster prints edge to edge and sets margin 0). */
const PRINT_PAGE_CSS = '@page { size: A4; margin: 14mm 14mm 16mm; }'

interface TocEntry {
  id: string
  text: string
  level: 2 | 3
}

/** The "On this page" list: every ## and ### heading outside code blocks. */
function tableOfContents(markdown: string): TocEntry[] {
  const entries: TocEntry[] = []
  let inFence = false
  for (const line of markdown.split('\n')) {
    if (line.trimStart().startsWith('```')) inFence = !inFence
    if (inFence) continue
    const m = line.match(/^(#{2,3})\s+(.+?)\s*$/)
    if (!m) continue
    const text = m[2].replace(/\[([^\]]+)\]\([^)]*\)/g, '$1').replace(/[`*]/g, '')
    entries.push({ id: slugify(text), text, level: m[1].length as 2 | 3 })
  }
  return entries
}

function groupsOf(pages: DocPageSummary[]): [string, DocPageSummary[]][] {
  const groups = new Map<string, DocPageSummary[]>()
  for (const p of pages) groups.set(p.group, [...(groups.get(p.group) ?? []), p])
  return [...groups]
}

function formatDate(iso: string) {
  return new Date(`${iso}T00:00:00`).toLocaleDateString('en-IN', { day: 'numeric', month: 'long', year: 'numeric' })
}

function Sidebar({ pages, current, onNavigate, superAdmin }: { pages: DocPageSummary[]; current: string; onNavigate?: () => void; superAdmin: boolean }) {
  const [filter, setFilter] = useState('')
  const q = filter.trim().toLowerCase()
  const shown = q ? pages.filter((p) => `${p.title} ${p.group}`.toLowerCase().includes(q)) : pages
  return (
    <nav aria-label="Documentation" className="space-y-6">
      <label className="block">
        <span className="sr-only">Filter pages</span>
        <input type="search" value={filter} onChange={(e) => setFilter(e.target.value)} placeholder="Filter pages"
          className="h-9 w-full rounded-lg border border-line bg-white px-3 text-sm outline-none focus:border-ink" />
      </label>
      {groupsOf(shown).map(([group, items]) => (
        <div key={group}>
          <p className="mb-2 px-2 text-[11px] font-semibold tracking-[0.14em] text-muted uppercase">{group}</p>
          <ul className="space-y-0.5">
            {items.map((p) => (
              <li key={p.slug}>
                <Link to={`/admin/docs/${p.slug}`} onClick={onNavigate} aria-current={p.slug === current ? 'page' : undefined}
                  className={`block rounded-lg px-2 py-1.5 text-sm ${p.slug === current ? 'bg-stone-200/70 font-medium text-ink' : 'text-stone-600 hover:bg-stone-100 hover:text-ink'}`}>
                  {p.title}
                  {superAdmin && p.studio && (
                    <span title="Studios see this page too" className="ml-1.5 rounded-full border border-line px-1.5 py-px align-middle text-[10px] text-muted">Studios</span>
                  )}
                </Link>
              </li>
            ))}
          </ul>
        </div>
      ))}
      {shown.length === 0 && <p className="px-2 text-sm text-muted">No pages match “{filter}”.</p>}
    </nav>
  )
}

function OnThisPage({ toc }: { toc: TocEntry[] }) {
  const [active, setActive] = useState('')
  useEffect(() => {
    const headings = toc.map((t) => document.getElementById(t.id)).filter((h): h is HTMLElement => h !== null)
    if (headings.length === 0 || typeof IntersectionObserver === 'undefined') return
    // The highlighted entry is the last heading that has scrolled past the top fifth of the screen.
    const observer = new IntersectionObserver(() => {
      const passed = headings.filter((h) => h.getBoundingClientRect().top < window.innerHeight * 0.2)
      setActive((passed.at(-1) ?? headings[0]).id)
    }, { rootMargin: '0px 0px -80% 0px', threshold: [0, 1] })
    headings.forEach((h) => observer.observe(h))
    return () => observer.disconnect()
  }, [toc])
  if (toc.length === 0) return null
  return (
    <nav aria-label="On this page">
      <p className="mb-3 text-[11px] font-semibold tracking-[0.14em] text-muted uppercase">On this page</p>
      <ul className="space-y-1.5 border-l border-line text-sm">
        {toc.map((t) => (
          <li key={t.id}>
            <a href={`#${t.id}`}
              className={`-ml-px block border-l py-0.5 ${t.level === 3 ? 'pl-6' : 'pl-3'} ${active === t.id ? 'border-ink font-medium text-ink' : 'border-transparent text-muted hover:text-ink'}`}>
              {t.text}
            </a>
          </li>
        ))}
      </ul>
    </nav>
  )
}

function Problem({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="mx-auto max-w-md py-24 text-center" role="alert">
      <p className="text-lg font-semibold text-ink">{title}</p>
      <div className="mt-2 text-sm text-muted">{children}</div>
    </div>
  )
}

/** Who a client guide is from: the studio's logo and name (McreatiK's own for the McreatiK login). */
function ClientGuideHeader({ brand }: { brand: Brand | null }) {
  const own = brand && !brand.mcreatik ? brand : null
  const logo = own ? own.logoUrl : mkLogo
  const name = own?.name ?? 'McreatiK Studios'
  return (
    <div data-testid="client-guide-brand" className="mb-8 rounded-2xl border border-line bg-white px-5 py-4 print:mb-6 print:rounded-none print:border-0 print:border-b print:px-0 print:pt-0">
      <p className="no-print mb-3 text-xs text-muted">
        A guide for your clients. Download the PDF and send it on WhatsApp or email; it carries your name and logo.
      </p>
      <div className="flex items-center gap-3">
        {logo && <img src={logo} alt="" className="h-10 w-auto max-w-[9rem] object-contain" />}
        <div>
          <p className="font-display text-lg font-semibold text-ink">{name}</p>
          {own?.instagram && <p className="text-xs text-muted">@{own.instagram.replace(/^@/, '')}</p>}
        </div>
      </div>
    </div>
  )
}

/**
 * The dashboard documentation: sidebar, article with diagrams, "On this page". McreatiK sees every page; a studio
 * sees only its guides (the server decides), and the client guides carry the studio's brand.
 */
export default function DocsPage() {
  const session = useSession()
  const superAdmin = session?.role === 'SUPER_ADMIN'
  const home = superAdmin ? 'overview' : 'sop-studio'
  const { slug = home } = useParams()
  const { hash } = useLocation()
  const [pages, setPages] = useState<DocPageSummary[] | null>(null)
  const [page, setPage] = useState<DocPage | null>(null)
  const [error, setError] = useState<{ slug: string; status: number; message: string } | null>(null)
  const [menuOpen, setMenuOpen] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const [brand, setBrand] = useState<Brand | null>(null)

  useEffect(() => {
    adminApi.account().then((a) => setBrand(a.brand ?? null)).catch(() => setBrand(null))
  }, [])

  useEffect(() => {
    const style = document.createElement('style')
    style.dataset.docsPrint = ''
    style.textContent = PRINT_PAGE_CSS
    document.head.appendChild(style)
    return () => style.remove()
  }, [])

  useEffect(() => {
    adminApi.docs().then(setPages).catch((e: unknown) => {
      setError({ slug: '*', status: e instanceof ApiError ? e.status : 0, message: e instanceof Error ? e.message : '' })
    })
  }, [attempt])

  useEffect(() => {
    let cancelled = false
    adminApi.doc(slug)
      .then((p) => {
        if (!cancelled) setPage(p)
      })
      .catch((e: unknown) => {
        if (!cancelled) setError({ slug, status: e instanceof ApiError ? e.status : 0, message: e instanceof Error ? e.message : '' })
      })
    return () => {
      cancelled = true
    }
  }, [slug, attempt])

  const current = page?.slug === slug ? page : null
  const toc = useMemo(() => (current ? tableOfContents(current.markdown) : []), [current])

  // A new page starts at the top, or at the section in the link (#photo-pipeline).
  useEffect(() => {
    if (!current) return
    const target = hash ? document.getElementById(decodeURIComponent(hash.slice(1))) : null
    if (!target) {
      window.scrollTo(0, 0)
      return
    }
    target.scrollIntoView()
    // Diagrams above the section finish drawing later and push it down: keep it in place while they do,
    // until the reader scrolls themselves or a few seconds have passed.
    const article = target.closest('article')
    if (!article || typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(() => target.scrollIntoView())
    const stop = () => observer.disconnect()
    observer.observe(article)
    const timer = setTimeout(stop, 4000)
    window.addEventListener('wheel', stop, { once: true, passive: true })
    window.addEventListener('touchstart', stop, { once: true, passive: true })
    window.addEventListener('keydown', stop, { once: true })
    return () => {
      stop()
      clearTimeout(timer)
      window.removeEventListener('wheel', stop)
      window.removeEventListener('touchstart', stop)
      window.removeEventListener('keydown', stop)
    }
  }, [current, hash])

  const clientGuide = CLIENT_GUIDES.has(slug)
  const brandName = brand && !brand.mcreatik ? brand.name : 'McreatiK'

  // The saved PDF is named after the page (browsers use the document title as the file name).
  useEffect(() => {
    if (!current) return
    const before = document.title
    document.title = clientGuide ? `${current.title} guide - ${brandName}` : `${current.title} - McreatiK Instant Memories`
    return () => {
      document.title = before
    }
  }, [current, clientGuide, brandName])

  const shownError = error && (error.slug === slug || error.slug === '*') ? error : null
  if (shownError?.status === 403) {
    return superAdmin
      ? <Problem title="Only McreatiK can view the documentation">It explains how the system is built and where its weak points are, so it is kept to the McreatiK login.</Problem>
      : <Problem title="The guides aren't available here">{shownError.message}</Problem>
  }

  const index = pages?.findIndex((p) => p.slug === slug) ?? -1
  const prev = index > 0 ? pages![index - 1] : null
  const next = pages && index >= 0 && index < pages.length - 1 ? pages[index + 1] : null
  const title = pages?.[index]?.title ?? current?.title ?? 'Documentation'

  return (
    <div className="mx-auto max-w-[90rem] lg:grid lg:grid-cols-[16rem_minmax(0,1fr)] xl:grid-cols-[16rem_minmax(0,1fr)_14rem]">
      <aside className="no-print hidden border-r border-line lg:block">
        <div className="sticky top-0 h-dvh overflow-y-auto px-4 py-6">
          <div className="mb-6 flex items-center justify-between px-2">
            <p className="text-sm font-semibold text-ink">{superAdmin ? 'Documentation' : 'Guides'}</p>
            {superAdmin && <span className="rounded-full border border-line px-2 py-0.5 text-[11px] text-muted">McreatiK only</span>}
          </div>
          {pages && <Sidebar pages={pages} current={slug} superAdmin={superAdmin} />}
        </div>
      </aside>

      <div className="no-print sticky top-0 z-10 border-b border-line bg-paper/95 px-4 py-2.5 backdrop-blur lg:hidden">
        <button type="button" onClick={() => setMenuOpen((o) => !o)} aria-expanded={menuOpen}
          className="flex w-full items-center justify-between rounded-lg border border-line bg-white px-3 py-2 text-sm">
          <span><span className="text-muted">{superAdmin ? 'Docs' : 'Guides'} / </span><span className="font-medium text-ink">{title}</span></span>
          <span aria-hidden="true" className="text-muted">{menuOpen ? '×' : '☰'}</span>
        </button>
        {menuOpen && pages && (
          <div className="anim-drop-in mt-3 max-h-[70dvh] overflow-y-auto pb-2">
            <Sidebar pages={pages} current={slug} superAdmin={superAdmin} onNavigate={() => setMenuOpen(false)} />
          </div>
        )}
      </div>

      <main className="min-w-0 px-4 py-8 sm:px-8 lg:px-12 lg:py-12">
        {shownError ? (
          shownError.status === 404 ? (
            <Problem title="This page doesn't exist">
              <Link to={`/admin/docs/${home}`} className="underline underline-offset-4">{superAdmin ? 'Go to the overview' : 'Go to the studio guide'}</Link>
            </Problem>
          ) : (
            <Problem title="The documentation could not be loaded">
              <button type="button" onClick={() => { setError(null); setAttempt((a) => a + 1) }} className="underline underline-offset-4">Try again</button>
            </Problem>
          )
        ) : !current ? (
          <div className="py-24"><ShutterLoader label="Loading documentation" /></div>
        ) : (
          <article className="mx-auto max-w-3xl" data-testid="doc-article">
            {clientGuide && <ClientGuideHeader brand={brand} />}
            <div className="no-print mb-3 flex items-center justify-between gap-3">
              <p className="text-xs font-semibold tracking-[0.16em] text-muted uppercase">{current.group}</p>
              <button type="button" onClick={() => window.print()}
                className="inline-flex h-8 shrink-0 items-center gap-1.5 rounded-lg border border-line bg-white px-3 text-sm font-medium text-ink hover:border-stone-400">
                <svg viewBox="0 0 16 16" className="size-3.5" aria-hidden="true">
                  <path d="M8 2v8m0 0L5 7m3 3l3-3M3 13h10" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
                </svg>
                Download PDF
              </button>
            </div>
            <div className="doc-print">
              <Markdown source={current.markdown} />
            </div>
            <p className="print-only mt-10 border-t border-line pt-3 text-xs text-muted">
              {clientGuide ? `${brandName} · Live photos by McreatiK Instant Memories` : 'McreatiK Instant Memories'} · live.mcreatik.com
            </p>
            <footer className="no-print mt-16 border-t border-line pt-6">
              <div className="grid gap-3 sm:grid-cols-2">
                {prev ? (
                  <Link to={`/admin/docs/${prev.slug}`} className="rounded-xl border border-line bg-white px-4 py-3 hover:border-stone-400">
                    <span className="block text-xs text-muted">Previous</span>
                    <span className="font-medium text-ink">← {prev.title}</span>
                  </Link>
                ) : <span />}
                {next && (
                  <Link to={`/admin/docs/${next.slug}`} className="rounded-xl border border-line bg-white px-4 py-3 text-right hover:border-stone-400">
                    <span className="block text-xs text-muted">Next</span>
                    <span className="font-medium text-ink">{next.title} →</span>
                  </Link>
                )}
              </div>
              <p className="mt-6 text-xs text-muted">Checked against the code on {formatDate(current.reviewedOn)}.</p>
            </footer>
          </article>
        )}
      </main>

      <aside className="no-print hidden xl:block">
        <div className="sticky top-0 max-h-dvh overflow-y-auto py-12 pr-4">
          {current && <OnThisPage toc={toc} />}
        </div>
      </aside>
    </div>
  )
}
