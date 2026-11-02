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

const ok = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

function signIn(role: 'SUPER_ADMIN' | 'STUDIO') {
  auth.signIn({ token: 't', expiresAt: new Date(Date.now() + 3_600_000).toISOString(), email: 'me@test', displayName: 'Me', role, mustChangePassword: false })
}

function serve(role: 'SUPER_ADMIN' | 'STUDIO') {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const path = new URL(String(input)).pathname
    if (path === '/api/admin/account') return ok({ email: 'me@test', displayName: 'Me', role, mustChangePassword: false })
    if (role !== 'SUPER_ADMIN') return ok({ code: 'FORBIDDEN', message: 'Only McreatiK can do this' }, 403)
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

  it('keeps studios out: no Docs link, and the page explains why', async () => {
    signIn('STUDIO')
    serve('STUDIO')
    open('/docs/backend')
    expect(await screen.findByText('Only McreatiK can view the documentation')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Docs' })).not.toBeInTheDocument()
    expect(screen.queryByTestId('doc-article')).not.toBeInTheDocument()
  })

  it('says so when a page does not exist', async () => {
    signIn('SUPER_ADMIN')
    serve('SUPER_ADMIN')
    open('/docs/nope')
    expect(await screen.findByText("This page doesn't exist")).toBeInTheDocument()
  })
})
