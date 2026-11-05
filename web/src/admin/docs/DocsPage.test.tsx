import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import AdminApp from '../AdminApp'
import { auth } from '../auth'

vi.mock('mermaid', () => ({
  default: {
    initialize: vi.fn(),
    render: vi.fn(async () => ({ svg: '<svg data-drawn="yes"></svg>' })),
  },
}))

const pages = [
  { slug: 'overview', title: 'Overview', group: 'Getting started' },
  { slug: 'backend', title: 'Backend', group: 'Codebase' },
  { slug: 'glossary', title: 'Glossary', group: 'Reference' },
]

const backend = {
  ...pages[1],
  reviewedOn: '2026-11-02',
  markdown: [
    '# Backend',
    '',
    'Intro text.',
    '',
    '## Request lifecycle',
    '',
    '```mermaid',
    'flowchart LR',
    '  A --> B',
    '```',
    '',
    '### Rate limit',
    '',
    '| Path | Limit |',
    '| --- | --- |',
    '| login | 10 |',
    '',
    '```bash',
    '# not a heading',
    'deploy.sh',
    '```',
  ].join('\n'),
}

const studioPages = [
  { slug: 'sop-studio', title: 'Studios', group: 'SOPs', studio: true },
  { slug: 'sop-couple', title: 'Couples (clients)', group: 'SOPs', studio: true },
]

const studioGuide = { ...studioPages[0], reviewedOn: '2026-11-05', markdown: '# Studio SOP\n\nHow to run an event.' }
const coupleGuide = { ...studioPages[1], reviewedOn: '2026-11-05', markdown: '# Couple SOP\n\nHow to pick your album photos.' }

const studioBrand = { name: 'Lens & Light', logoUrl: 'https://cdn.test/logo.png', instagram: 'lensandlight', mcreatik: false }

const ok = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

function signIn(role: 'SUPER_ADMIN' | 'STUDIO') {
  auth.signIn({ token: 't', expiresAt: new Date(Date.now() + 3_600_000).toISOString(), email: 'me@test', displayName: 'Me', role, mustChangePassword: false })
}

function serve(role: 'SUPER_ADMIN' | 'STUDIO') {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const path = new URL(String(input)).pathname
    if (path === '/api/admin/account') {
      return ok({ email: 'me@test', displayName: 'Me', role, mustChangePassword: false, ...(role === 'STUDIO' ? { brand: studioBrand } : {}) })
    }
    if (role !== 'SUPER_ADMIN') {
      if (path === '/api/admin/docs') return ok(studioPages)
      if (path === '/api/admin/docs/sop-studio') return ok(studioGuide)
      if (path === '/api/admin/docs/sop-couple') return ok(coupleGuide)
      return ok({ code: 'NOT_FOUND', message: 'Page not found' }, 404)
    }
    if (path === '/api/admin/docs') return ok(pages)
    if (path === '/api/admin/docs/backend') return ok(backend)
    return ok({ code: 'NOT_FOUND', message: 'Page not found' }, 404)
  }))
}

function open(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AdminApp />
    </MemoryRouter>,
  )
}

describe('Documentation', () => {
  afterEach(() => {
    auth.signOut()
    vi.unstubAllGlobals()
  })

  it('shows McreatiK the sidebar, the page, its diagram and an on-this-page list', async () => {
    signIn('SUPER_ADMIN')
    serve('SUPER_ADMIN')
    open('/docs/backend')

    const article = await screen.findByTestId('doc-article')
    expect(within(article).getByRole('heading', { level: 1, name: 'Backend' })).toBeInTheDocument()
    expect(within(article).getByRole('heading', { level: 2, name: /Request lifecycle/ })).toHaveAttribute('id', 'request-lifecycle')
    expect(within(article).getByRole('table')).toHaveTextContent('login')
    await waitFor(() => expect(article.querySelector('svg[data-drawn="yes"]')).not.toBeNull())

    const sidebar = screen.getAllByRole('navigation', { name: 'Documentation' })[0]
    expect(within(sidebar).getByText('Getting started')).toBeInTheDocument()
    expect(within(sidebar).getByRole('link', { name: 'Backend' })).toHaveAttribute('aria-current', 'page')

    const toc = screen.getByRole('navigation', { name: 'On this page' })
    expect(within(toc).getAllByRole('link').map((a) => a.getAttribute('href'))).toEqual(['#request-lifecycle', '#rate-limit'])

    expect(within(article).getByRole('link', { name: /Previous.*Overview/ })).toHaveAttribute('href', '/admin/docs/overview')
    expect(within(article).getByRole('link', { name: /Next.*Glossary/ })).toHaveAttribute('href', '/admin/docs/glossary')
    expect(screen.getByRole('link', { name: 'Docs' })).toBeInTheDocument()
  })

  it('filters the sidebar', async () => {
    const user = userEvent.setup()
    signIn('SUPER_ADMIN')
    serve('SUPER_ADMIN')
    open('/docs/backend')
    const sidebar = (await screen.findAllByRole('navigation', { name: 'Documentation' }))[0]
    await user.type(within(sidebar).getByPlaceholderText('Filter pages'), 'gloss')
    expect(within(sidebar).getByRole('link', { name: 'Glossary' })).toBeInTheDocument()
    expect(within(sidebar).queryByRole('link', { name: 'Backend' })).not.toBeInTheDocument()
  })

  it('gives studios a Guides link that opens the studio guide, with only their pages', async () => {
    signIn('STUDIO')
    serve('STUDIO')
    open('/docs')
    const article = await screen.findByTestId('doc-article')
    expect(within(article).getByRole('heading', { level: 1, name: 'Studio SOP' })).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: 'Guides' }).length).toBeGreaterThan(0)
    expect(screen.queryByRole('link', { name: 'Docs' })).not.toBeInTheDocument()
    expect(screen.queryByText('McreatiK only')).not.toBeInTheDocument()
    const sidebar = screen.getAllByRole('navigation', { name: 'Documentation' })[0]
    expect(within(sidebar).getAllByRole('link').map((a) => a.textContent)).toEqual(['Studios', 'Couples (clients)'])
    expect(screen.queryByTestId('client-guide-brand')).not.toBeInTheDocument()
  })

  it('shows a client guide under the studio brand and prints it as a PDF', async () => {
    const user = userEvent.setup()
    const print = vi.fn()
    vi.stubGlobal('print', print)
    signIn('STUDIO')
    serve('STUDIO')
    open('/docs/sop-couple')
    const header = await screen.findByTestId('client-guide-brand')
    await waitFor(() => expect(within(header).getByText('Lens & Light')).toBeInTheDocument())
    expect(within(header).getByText('@lensandlight')).toBeInTheDocument()
    expect(header.querySelector('img')).toHaveAttribute('src', 'https://cdn.test/logo.png')
    expect(screen.getByText(/Lens & Light · Live photos by McreatiK Instant Memories/)).toBeInTheDocument()
    await waitFor(() => expect(document.title).toBe('Couples (clients) guide - Lens & Light'))
    await user.click(screen.getByRole('button', { name: 'Download PDF' }))
    expect(print).toHaveBeenCalledOnce()
    expect(document.head.querySelector('style[data-docs-print]')?.textContent).toContain('margin: 14mm')
  })

  it('tells a studio an internal page does not exist', async () => {
    signIn('STUDIO')
    serve('STUDIO')
    open('/docs/backend')
    expect(await screen.findByText("This page doesn't exist")).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Go to the studio guide' })).toHaveAttribute('href', '/admin/docs/sop-studio')
  })

  it('says so when a page does not exist', async () => {
    signIn('SUPER_ADMIN')
    serve('SUPER_ADMIN')
    open('/docs/nope')
    expect(await screen.findByText("This page doesn't exist")).toBeInTheDocument()
  })
})
