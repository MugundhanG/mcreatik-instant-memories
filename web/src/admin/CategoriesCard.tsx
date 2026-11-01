import { useState, type FormEvent } from 'react'
import { adminApi, ApiError } from '../lib/api'
import { formatCount } from '../lib/format'
import type { Category } from '../lib/types'
import { Button, Card, ErrorText, inputClass } from './ui'
import { useConfirm } from './useConfirm'

/** The event's photo categories (Couple, Cake cutting, …): what clients browse by. Rename, add, remove. */
export function CategoriesCard({ eventId, categories, onChange }: { eventId: string; categories: Category[]; onChange: () => void }) {
  const [name, setName] = useState('')
  const [editing, setEditing] = useState<{ id: string; name: string } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [confirm, confirmDialog] = useConfirm()

  const run = async (action: () => Promise<unknown>) => {
    setError(null)
    try {
      await action()
      onChange()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not save')
    }
  }

  const add = (e: FormEvent) => {
    e.preventDefault()
    if (!name.trim()) return
    void run(async () => {
      await adminApi.createCategory(eventId, name.trim())
      setName('')
    })
  }

  const rename = (e: FormEvent) => {
    e.preventDefault()
    if (!editing) return
    void run(async () => {
      await adminApi.renameCategory(eventId, editing.id, editing.name.trim())
      setEditing(null)
    })
  }

  const remove = async (c: Category) => {
    const ok = await confirm({
      title: `Remove “${c.name}”?`,
      body: c.photos > 0 ? `Its ${formatCount(c.photos)} photos stay in the event, just not sorted.` : 'It has no photos.',
      confirmLabel: 'Remove category',
      danger: true,
    })
    if (ok) void run(() => adminApi.deleteCategory(eventId, c.id))
  }

  return (
    <Card title="Photo categories">
      {confirmDialog}
      <p className="mb-3 text-sm text-muted">Clients browse photos by these. Sort photos in the Photos list below (Select → Move to), or upload straight into a category.</p>
      <ul className="flex flex-wrap gap-2" data-testid="categories">
        {categories.map((c) => (
          <li key={c.id} className="flex items-center gap-1 rounded-full border border-line bg-white py-1 pr-1 pl-3 text-sm">
            {editing?.id === c.id ? (
              <form onSubmit={rename} className="flex items-center gap-1">
                <input autoFocus aria-label="Category name" maxLength={60} className="h-7 w-36 rounded border border-line px-2 text-sm" value={editing.name}
                  onChange={(e) => setEditing({ id: c.id, name: e.target.value })} />
                <button type="submit" className="rounded-full px-2 py-0.5 text-xs font-medium hover:bg-stone-100">Save</button>
              </form>
            ) : (
              <>
                <button type="button" onClick={() => setEditing({ id: c.id, name: c.name })} className="hover:underline" title="Rename">{c.name}</button>
                <span className="text-xs text-muted tabular-nums">{formatCount(c.photos)}</span>
                <button type="button" aria-label={`Remove ${c.name}`} onClick={() => remove(c)} className="flex size-6 items-center justify-center rounded-full text-muted hover:bg-stone-100 hover:text-red-700">×</button>
              </>
            )}
          </li>
        ))}
      </ul>
      <form onSubmit={add} className="mt-3 flex gap-2">
        <input className={`${inputClass} max-w-xs`} maxLength={60} value={name} onChange={(e) => setName(e.target.value)} placeholder="New category, e.g. Haldi" aria-label="New category" />
        <Button type="submit" disabled={!name.trim()}>Add</Button>
      </form>
      <div className="mt-2"><ErrorText>{error}</ErrorText></div>
    </Card>
  )
}
