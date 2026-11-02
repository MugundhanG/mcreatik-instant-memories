import { useEffect, useId, useRef, useState } from 'react'

type MermaidApi = (typeof import('mermaid'))['default']

let loading: Promise<MermaidApi> | null = null

/** Mermaid is large, so it is downloaded only when a page actually has a diagram. */
function loadMermaid(): Promise<MermaidApi> {
  loading ??= import('mermaid').then(({ default: mermaid }) => {
    mermaid.initialize({
      startOnLoad: false,
      // Strict: no scripts or click handlers inside diagrams, labels are sanitised.
      securityLevel: 'strict',
      theme: 'base',
      fontFamily: '"Inter Variable", ui-sans-serif, system-ui, sans-serif',
      themeVariables: {
        fontSize: '14px',
        primaryColor: '#ffffff',
        primaryTextColor: '#1c1917',
        primaryBorderColor: '#a8a29e',
        secondaryColor: '#f5f5f4',
        tertiaryColor: '#faf9f7',
        lineColor: '#78716c',
        textColor: '#1c1917',
        clusterBkg: '#faf9f7',
        clusterBorder: '#d6d3d1',
        noteBkgColor: '#fef3c7',
        noteBorderColor: '#d6d3d1',
        actorBkg: '#ffffff',
        actorBorder: '#a8a29e',
        signalColor: '#44403c',
        labelBoxBkgColor: '#ffffff',
      },
      flowchart: { curve: 'basis', padding: 12, htmlLabels: true },
      sequence: { mirrorActors: false, useMaxWidth: true },
    })
    return mermaid
  })
  return loading
}

/** One diagram from a ```mermaid block. If it cannot be drawn, the source is shown instead. */
export function Mermaid({ code }: { code: string }) {
  const id = `mermaid-${useId().replace(/[^a-zA-Z0-9_-]/g, '')}`
  const ref = useRef<HTMLDivElement>(null)
  const [state, setState] = useState<'loading' | 'ready' | 'failed'>('loading')

  useEffect(() => {
    let cancelled = false
    loadMermaid()
      .then((mermaid) => mermaid.render(id, code))
      .then(({ svg }) => {
        if (cancelled || !ref.current) return
        // Sanitised by Mermaid (securityLevel 'strict'); the source is our own server's Markdown.
        ref.current.innerHTML = svg
        // On a phone, keep wide diagrams readable: they scroll sideways instead of shrinking to tiny text.
        const drawn = ref.current.querySelector('svg')
        const natural = drawn ? parseFloat(drawn.style.maxWidth) : NaN
        if (drawn && natural > 0) drawn.style.minWidth = `${Math.min(natural, 680)}px`
        setState('ready')
      })
      .catch(() => {
        if (!cancelled) setState('failed')
      })
    return () => {
      cancelled = true
    }
  }, [id, code])

  if (state === 'failed') {
    return (
      <pre className="my-6 overflow-x-auto rounded-xl border border-line bg-stone-50 p-4 text-xs leading-relaxed text-muted">
        <code>{code}</code>
      </pre>
    )
  }
  return (
    <figure className="my-6 overflow-x-auto rounded-xl border border-line bg-white p-4 sm:p-6" data-testid="diagram">
      {state === 'loading' && <p className="py-10 text-center text-sm text-muted">Drawing diagram…</p>}
      <div ref={ref} className="mermaid-diagram flex justify-center [&_svg]:h-auto [&_svg]:max-w-full" />
    </figure>
  )
}
