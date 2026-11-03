import { useEffect, useState, type ReactNode } from 'react'
import ReactMarkdown, { type Components } from 'react-markdown'
import { Link } from 'react-router'
import remarkGfm from 'remark-gfm'
import { slugify, textOf, type TreeNode } from './docsText'
import { Mermaid } from './Mermaid'
import { adminApi } from '../../lib/api'

function Heading({ level, node, children }: { level: 2 | 3; node?: TreeNode; children: ReactNode }) {
  const id = slugify(textOf(node))
  const Tag = level === 2 ? 'h2' : 'h3'
  const size = level === 2 ? 'mt-12 border-t border-line pt-8 text-2xl' : 'mt-8 text-lg'
  return (
    <Tag id={id} className={`group scroll-mt-6 font-semibold tracking-tight text-ink ${size}`}>
      {children}
      <a href={`#${id}`} aria-label="Link to this section" className="ml-2 text-muted no-underline opacity-0 group-hover:opacity-100 focus:opacity-100">
        #
      </a>
    </Tag>
  )
}

function CodeBlock({ code, language }: { code: string; language: string }) {
  const [copied, setCopied] = useState(false)
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(code)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch {
      // Clipboard refused: the text can still be selected by hand.
    }
  }
  return (
    <div className="group relative my-5">
      {language && <span className="absolute top-2 left-4 text-[11px] tracking-wider text-stone-400 uppercase">{language}</span>}
      <button type="button" onClick={copy}
        className="absolute top-2 right-2 rounded-md border border-stone-600 px-2 py-0.5 text-xs text-stone-300 hover:bg-stone-700">
        {copied ? 'Copied' : 'Copy'}
      </button>
      <pre className={`overflow-x-auto rounded-xl bg-stone-900 px-4 pb-4 text-[13px] leading-relaxed text-stone-100 ${language ? 'pt-8' : 'pt-4'}`}>
        <code>{code}</code>
      </pre>
    </div>
  )
}

/**
 * A screenshot or illustration from `docs/images/`, written in a page as `![Caption](images/name.webp)`.
 * Images are fetched with the login token (they are as private as the pages), shown with their caption,
 * and open full size on click.
 */
function DocImage({ src, alt }: { src?: string; alt?: string }) {
  const name = src?.startsWith('images/') ? src.slice('images/'.length) : null
  const [url, setUrl] = useState<string | null>(null)
  const [failed, setFailed] = useState(false)
  const [zoomed, setZoomed] = useState(false)
  useEffect(() => {
    if (!name) return
    let objectUrl: string | null = null
    let cancelled = false
    adminApi.docImage(name)
      .then((blob) => {
        if (cancelled) return
        objectUrl = URL.createObjectURL(blob)
        setUrl(objectUrl)
      })
      .catch(() => !cancelled && setFailed(true))
    return () => {
      cancelled = true
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [name])
  useEffect(() => {
    if (!zoomed) return
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setZoomed(false)
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [zoomed])
  if (!name) return null
  return (
    <figure className="my-6" data-testid="doc-image">
      {url ? (
        <button type="button" onClick={() => setZoomed(true)} aria-label={`Enlarge: ${alt ?? 'image'}`}
          className="block w-full cursor-zoom-in overflow-hidden rounded-xl border border-line bg-stone-50 shadow-sm">
          <img src={url} alt={alt ?? ''} className="mx-auto block h-auto max-h-[640px] max-w-full" />
        </button>
      ) : (
        <div className={`flex h-48 items-center justify-center rounded-xl border border-line text-sm text-muted ${failed ? '' : 'animate-pulse bg-stone-100'}`}>
          {failed ? 'Image could not be loaded' : ''}
        </div>
      )}
      {alt && <figcaption className="mt-2 text-center text-sm text-muted">{alt}</figcaption>}
      {zoomed && url && (
        <div role="dialog" aria-modal="true" aria-label={alt ?? 'Image'} onClick={() => setZoomed(false)}
          className="anim-fade-in fixed inset-0 z-50 flex cursor-zoom-out items-center justify-center bg-black/80 p-4">
          <img src={url} alt={alt ?? ''} className="max-h-full max-w-full rounded-lg shadow-2xl" />
        </div>
      )}
    </figure>
  )
}

const CALLOUTS: Record<string, string> = {
  Note: 'border-sky-300 bg-sky-50',
  Warning: 'border-amber-400 bg-amber-50',
  Tip: 'border-emerald-300 bg-emerald-50',
}

const components: Components = {
  h1: ({ children }) => <h1 className="text-3xl font-semibold tracking-tight text-ink sm:text-4xl">{children}</h1>,
  h2: ({ node, children }) => <Heading level={2} node={node as TreeNode}>{children}</Heading>,
  h3: ({ node, children }) => <Heading level={3} node={node as TreeNode}>{children}</Heading>,
  // A paragraph holding only an image becomes the image's <figure> (a figure can't sit inside a <p>).
  p: ({ node, children }) => {
    const kids = ((node as TreeNode | undefined)?.children ?? []).filter((c) => !(c.type === 'text' && !String(c.value ?? '').trim()))
    if (kids.length > 0 && kids.every((c) => c.tagName === 'img')) return <>{children}</>
    return <p className="my-4 leading-7 text-stone-700">{children}</p>
  },
  img: ({ src, alt }) => <DocImage src={typeof src === 'string' ? src : undefined} alt={alt} />,
  a: ({ href, children }) => href?.startsWith('/admin/docs/') ? (
    <Link to={href} className="font-medium text-ink underline decoration-stone-300 underline-offset-4 hover:decoration-ink">{children}</Link>
  ) : (
    <a href={href} className="font-medium text-ink underline decoration-stone-300 underline-offset-4 hover:decoration-ink"
      {...(href?.startsWith('http') ? { target: '_blank', rel: 'noreferrer' } : {})}>
      {children}
    </a>
  ),
  ul: ({ children }) => <ul className="my-4 list-disc space-y-1.5 pl-6 leading-7 text-stone-700 marker:text-stone-400">{children}</ul>,
  ol: ({ children }) => <ol className="my-4 list-decimal space-y-1.5 pl-6 leading-7 text-stone-700 marker:text-stone-500">{children}</ol>,
  strong: ({ children }) => <strong className="font-semibold text-ink">{children}</strong>,
  code: ({ children }) => (
    <code className="rounded-md border border-line bg-stone-100 px-1.5 py-0.5 font-mono text-[0.85em] text-ink">{children}</code>
  ),
  pre: ({ node }) => {
    const code = (node as TreeNode | undefined)?.children?.[0]
    const className = code?.properties?.className
    const language = (Array.isArray(className) ? String(className[0] ?? '') : '').replace('language-', '')
    const text = textOf(code).replace(/\n$/, '')
    if (language === 'mermaid') return <Mermaid code={text} />
    return <CodeBlock code={text} language={language} />
  },
  blockquote: ({ node, children }) => {
    const first = textOf(node as TreeNode).trim()
    const kind = Object.keys(CALLOUTS).find((k) => first.startsWith(k)) ?? 'Note'
    return <blockquote className={`my-6 rounded-r-xl border-l-4 px-4 py-1 [&_p]:my-2 ${CALLOUTS[kind]}`}>{children}</blockquote>
  },
  table: ({ children }) => (
    <div className="my-6 overflow-x-auto rounded-xl border border-line">
      <table className="w-full border-collapse text-left text-sm [&_tr:last-child_td]:border-b-0">{children}</table>
    </div>
  ),
  thead: ({ children }) => <thead className="bg-stone-50 text-xs tracking-wide text-muted uppercase">{children}</thead>,
  th: ({ children }) => <th className="border-b border-line px-3 py-2.5 font-medium whitespace-nowrap">{children}</th>,
  td: ({ children }) => <td className="border-b border-line px-3 py-2.5 align-top leading-6 text-stone-700">{children}</td>,
  hr: () => <hr className="my-10 border-line" />,
}

/** Renders a documentation page's Markdown as React elements (raw HTML in the source is ignored). */
export function Markdown({ source }: { source: string }) {
  return (
    <ReactMarkdown remarkPlugins={[remarkGfm]} components={components}>
      {source}
    </ReactMarkdown>
  )
}
