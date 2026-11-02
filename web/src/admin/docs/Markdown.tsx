import { useState, type ReactNode } from 'react'
import ReactMarkdown, { type Components } from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { slugify, textOf, type TreeNode } from './docsText'
import { Mermaid } from './Mermaid'

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

const CALLOUTS: Record<string, string> = {
  Note: 'border-sky-300 bg-sky-50',
  Warning: 'border-amber-400 bg-amber-50',
}

const components: Components = {
  h1: ({ children }) => <h1 className="text-3xl font-semibold tracking-tight text-ink sm:text-4xl">{children}</h1>,
  h2: ({ node, children }) => <Heading level={2} node={node as TreeNode}>{children}</Heading>,
  h3: ({ node, children }) => <Heading level={3} node={node as TreeNode}>{children}</Heading>,
  p: ({ children }) => <p className="my-4 leading-7 text-stone-700">{children}</p>,
  a: ({ href, children }) => (
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
