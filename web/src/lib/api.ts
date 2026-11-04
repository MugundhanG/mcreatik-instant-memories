import type { PreparedPhoto } from '../admin/prepareUpload'
import type { Account, AdminPhoto, AdminPhotoPage, AdminPhotoQuery, AlbumSelection, BillingSettings, BillingSummary, Brand, Category, EventClient, EventDetail, EventPayment, EventStats, EventStatus, EventSummary, DocPage, OnlineOrder, OnlinePaymentResult, DocPageSummary, GuestAnalytics, LoginResponse, OriginalUploadSession, PhotoFacets, Plan, PreparedZip, PublicEvent, PublicPhotoPage, PublicPricing, Studio, StudioBilling, UploaderCredentials } from './types'

export type BulkPhotoAction = 'LIKE' | 'UNLIKE' | 'HIDE' | 'SHOW' | 'DELETE'

export const API_BASE_URL: string = (import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080').replace(/\/$/, '')

/** Starts a prepared ZIP download; the server sends it as a file, so this page stays open. */
export function openZip(zip: { path: string }) {
  window.location.assign(`${API_BASE_URL}${zip.path}`)
}

export class ApiError extends Error {
  readonly status: number
  readonly code: string

  constructor(status: number, code: string, message: string) {
    super(message)
    this.status = status
    this.code = code
  }
}

type TokenProvider = () => string | null
let tokenProvider: TokenProvider = () => null
let onUnauthorized: () => void = () => {}

export function configureAuth(provider: TokenProvider, unauthorized: () => void) {
  tokenProvider = provider
  onUnauthorized = unauthorized
}

async function request<T>(path: string, init: RequestInit = {}, auth = false): Promise<T> {
  const headers = new Headers(init.headers)
  // JSON bodies are strings; FormData (photo uploads) sets its own multipart Content-Type.
  if (typeof init.body === 'string' && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  if (auth) {
    const token = tokenProvider()
    if (token) headers.set('Authorization', `Bearer ${token}`)
  }
  const response = await fetch(`${API_BASE_URL}${path}`, { ...init, headers })
  if (response.status === 401 && auth) onUnauthorized()
  if (!response.ok) {
    let code = 'HTTP_' + response.status
    let message = response.statusText || 'Request failed'
    try {
      const body = await response.json()
      code = body.code ?? code
      message = body.message ?? message
    } catch {
      // non-JSON error body
    }
    throw new ApiError(response.status, code, message)
  }
  if (response.status === 204) return undefined as T
  return (await response.json()) as T
}

const json = (body: unknown) => JSON.stringify(body)

/** Fetches a logged-in file (a documentation screenshot) as a Blob, for an object URL. */
async function requestBlob(path: string): Promise<Blob> {
  const headers = new Headers()
  const token = tokenProvider()
  if (token) headers.set('Authorization', `Bearer ${token}`)
  const response = await fetch(`${API_BASE_URL}${path}`, { headers })
  if (response.status === 401) onUnauthorized()
  if (!response.ok) throw new ApiError(response.status, 'HTTP_' + response.status, response.statusText || 'Request failed')
  return response.blob()
}

// ---- Guest API ----

export const publicApi = {
  pricing: () => request<PublicPricing>('/api/public/pricing'),
  event: (slug: string) => request<PublicEvent>(`/api/public/events/${encodeURIComponent(slug)}`),
  photos: (slug: string, opts: { before?: string; after?: string; limit?: number } = {}) => {
    const q = new URLSearchParams()
    if (opts.before) q.set('before', opts.before)
    if (opts.after) q.set('after', opts.after)
    if (opts.limit) q.set('limit', String(opts.limit))
    const qs = q.toString()
    return request<PublicPhotoPage>(`/api/public/events/${encodeURIComponent(slug)}/photos${qs ? `?${qs}` : ''}`)
  },
  streamUrl: (slug: string) => `${API_BASE_URL}/api/public/events/${encodeURIComponent(slug)}/stream`,
}

// ---- Admin API ----

export interface CreateEventInput {
  name: string
  eventDate: string
  status?: EventStatus
  slug?: string
  /** Super admin only: create the event for this studio. */
  ownerId?: string
}

export interface UpdateEventInput {
  name?: string
  slug?: string
  eventDate?: string
  status?: EventStatus
  coverPhotoId?: string
  retentionUntil?: string
  clearRetention?: boolean
}

export const adminApi = {
  /** McreatiK only: the internal documentation (the server refuses everyone else with 403). */
  docs: () => request<DocPageSummary[]>('/api/admin/docs', {}, true),
  doc: (slug: string) => request<DocPage>(`/api/admin/docs/${encodeURIComponent(slug)}`, {}, true),
  docImage: (name: string) => requestBlob(`/api/admin/docs/images/${encodeURIComponent(name)}`),
  /** Emails a temporary password if the account exists; the answer is the same either way. */
  forgotPassword: (email: string) =>
    request<void>('/api/auth/forgot-password', { method: 'POST', body: json({ email }) }),
  /** McreatiK only: turn the guests' gallery and the couple's gallery on or off. */
  setGalleryAccess: (eventId: string, access: { liveGallery?: boolean; clientGallery?: boolean }) =>
    request<EventDetail>(`/api/admin/events/${eventId}/access`, { method: 'PATCH', body: json(access) }, true),
  login: (email: string, password: string) =>
    request<LoginResponse>('/api/auth/login', { method: 'POST', body: json({ email, password }) }),
  events: () => request<EventSummary[]>('/api/admin/events', {}, true),
  createEvent: (input: CreateEventInput) =>
    request<EventDetail>('/api/admin/events', { method: 'POST', body: json(input) }, true),
  event: (id: string) => request<EventDetail>(`/api/admin/events/${id}`, {}, true),
  updateEvent: (id: string, input: UpdateEventInput) =>
    request<EventDetail>(`/api/admin/events/${id}`, { method: 'PATCH', body: json(input) }, true),
  deleteEvent: (id: string) => request<void>(`/api/admin/events/${id}`, { method: 'DELETE' }, true),
  stats: (id: string) => request<EventStats>(`/api/admin/events/${id}/stats`, {}, true),
  photos: (id: string, query: AdminPhotoQuery = {}) => {
    const q = new URLSearchParams()
    for (const [k, v] of Object.entries(query)) if (v !== undefined && v !== '') q.set(k, String(v))
    const qs = q.toString()
    return request<AdminPhotoPage>(`/api/admin/events/${id}/photos${qs ? `?${qs}` : ''}`, {}, true)
  },
  photoFacets: (id: string) => request<PhotoFacets>(`/api/admin/events/${id}/photo-facets`, {}, true),
  updatePhoto: (photoId: string, input: { hidden?: boolean; liked?: boolean }) =>
    request<AdminPhoto>(`/api/admin/photos/${photoId}`, { method: 'PATCH', body: json(input) }, true),
  /** Dashboard upload: the browser-made gallery and thumbnail JPEGs plus the original's details. */
  uploadPhoto: (eventId: string, file: File, p: PreparedPhoto, withOriginal = false, categoryId?: string) => {
    const form = new FormData()
    form.append('web', p.web, 'web.jpg')
    form.append('thumbnail', p.thumbnail, 'thumb.jpg')
    form.append('fileName', file.name)
    form.append('fileSize', String(file.size))
    form.append('mimeType', file.type)
    form.append('checksumSha256', p.checksumSha256)
    form.append('width', String(p.width))
    form.append('height', String(p.height))
    if (p.capturedAt) form.append('capturedAt', p.capturedAt)
    if (withOriginal) form.append('original', 'true')
    if (categoryId) form.append('categoryId', categoryId)
    return request<{ outcome: 'UPLOAD' | 'DUPLICATE'; photoId: string }>(`/api/admin/events/${eventId}/photos`, { method: 'POST', body: form }, true)
  },
  /** Original-quality upload, step 1: where to PUT the full file (straight to storage, not through the server). */
  originalUpload: (eventId: string, file: File, checksumSha256: string, categoryId?: string) =>
    request<OriginalUploadSession>(`/api/admin/events/${eventId}/original-uploads`, {
      method: 'POST',
      body: json({ fileName: file.name, fileSize: file.size, mimeType: file.type, checksumSha256, categoryId: categoryId ?? null }),
    }, true),
  /** For a photo already in the gallery: keep the original that was just PUT. */
  attachOriginal: (eventId: string, photoId: string) =>
    request<void>(`/api/admin/events/${eventId}/photos/${photoId}/original`, { method: 'POST' }, true),
  categories: (eventId: string) => request<Category[]>(`/api/admin/events/${eventId}/categories`, {}, true),
  createCategory: (eventId: string, name: string) =>
    request<Category>(`/api/admin/events/${eventId}/categories`, { method: 'POST', body: json({ name }) }, true),
  renameCategory: (eventId: string, categoryId: string, name: string) =>
    request<void>(`/api/admin/events/${eventId}/categories/${categoryId}`, { method: 'PATCH', body: json({ name }) }, true),
  deleteCategory: (eventId: string, categoryId: string) =>
    request<void>(`/api/admin/events/${eventId}/categories/${categoryId}`, { method: 'DELETE' }, true),
  /** categoryId null: back to "not sorted". */
  assignCategory: (eventId: string, photoIds: string[], categoryId: string | null) =>
    request<{ moved: number }>(`/api/admin/events/${eventId}/photos/category`, { method: 'POST', body: json({ photoIds, categoryId }) }, true),
  /** Like, hide or delete many photos of one event at once (Select mode). */
  bulkPhotos: (eventId: string, photoIds: string[], action: BulkPhotoAction) =>
    request<{ changed: number }>(`/api/admin/events/${eventId}/photos/bulk`, { method: 'POST', body: json({ photoIds, action }) }, true),
  clients: (eventId: string) => request<EventClient[]>(`/api/admin/events/${eventId}/clients`, {}, true),
  createClient: (eventId: string, input: { name: string; email: string; password: string; phone?: string }) =>
    request<EventClient>(`/api/admin/events/${eventId}/clients`, { method: 'POST', body: json(input) }, true),
  updateClient: (eventId: string, clientId: string, input: { newPassword?: string; disabled?: boolean; phone?: string }) =>
    request<void>(`/api/admin/events/${eventId}/clients/${clientId}`, { method: 'PATCH', body: json(input) }, true),
  deleteClient: (eventId: string, clientId: string) =>
    request<void>(`/api/admin/events/${eventId}/clients/${clientId}`, { method: 'DELETE' }, true),
  /** Several photos (or the couple's whole album selection) as one ZIP; open it with {@link openZip}. */
  prepareZip: (eventId: string, input: { photoIds?: string[]; album?: boolean }) =>
    request<PreparedZip>(`/api/admin/events/${eventId}/downloads`, { method: 'POST', body: json(input) }, true),
  album: (eventId: string) => request<AlbumSelection>(`/api/admin/events/${eventId}/album`, {}, true),
  setAlbumLimit: (eventId: string, limit: number | null) =>
    request<void>(`/api/admin/events/${eventId}/album`, { method: 'PUT', body: json({ limit }) }, true),
  deletePhoto: (photoId: string) => request<void>(`/api/admin/photos/${photoId}`, { method: 'DELETE' }, true),
  originalUrl: (photoId: string) => request<{ url: string }>(`/api/admin/photos/${photoId}/original`, {}, true),
  createUploader: (eventId: string, name: string) =>
    request<UploaderCredentials>(`/api/admin/events/${eventId}/uploaders`, { method: 'POST', body: json({ name }) }, true),
  rotateUploader: (uploaderId: string) =>
    request<UploaderCredentials>(`/api/admin/uploaders/${uploaderId}/rotate-token`, { method: 'POST' }, true),
  updateAccount: (input: { displayName?: string; instagram?: string; logoScale?: number }) =>
    request<Account>('/api/admin/account', { method: 'PATCH', body: json(input) }, true),
  uploadLogo: (png: Blob) => {
    const form = new FormData()
    form.append('logo', png, 'logo.png')
    return request<Account>('/api/admin/account/logo', { method: 'POST', body: form }, true)
  },
  removeLogo: () => request<Account>('/api/admin/account/logo', { method: 'DELETE' }, true),
  analytics: (eventId: string) => request<GuestAnalytics>(`/api/admin/events/${eventId}/analytics`, {}, true),
  /** McreatiK sets up a studio's branding (onboarding). */
  updateStudioBranding: (studioId: string, input: { displayName?: string; instagram?: string; logoScale?: number }) =>
    request<Brand>(`/api/admin/studios/${studioId}/branding`, { method: 'PATCH', body: json(input) }, true),
  uploadStudioLogo: (studioId: string, png: Blob) => {
    const form = new FormData()
    form.append('logo', png, 'logo.png')
    return request<Brand>(`/api/admin/studios/${studioId}/logo`, { method: 'POST', body: form }, true)
  },
  removeStudioLogo: (studioId: string) => request<Brand>(`/api/admin/studios/${studioId}/logo`, { method: 'DELETE' }, true),
  billing: () => request<BillingSummary>('/api/admin/billing', {}, true),
  /** The studio paid by UPI: the events are usable at once, McreatiK checks the transaction later. */
  startOnlinePayment: (events: number) =>
    request<OnlineOrder>('/api/admin/billing/online-orders', { method: 'POST', body: json({ events }) }, true),
  confirmOnlinePayment: (paymentId: string, razorpayPaymentId: string, razorpaySignature: string) =>
    request<OnlinePaymentResult>(`/api/admin/billing/online-orders/${paymentId}/confirm`,
      { method: 'POST', body: json({ razorpayPaymentId, razorpaySignature }) }, true),
  payForEvents: (events: number, utr: string) =>
    request<EventPayment>('/api/admin/billing/payments', { method: 'POST', body: json({ events, utr }) }, true),
  payments: (status?: EventPayment['status']) =>
    request<EventPayment[]>(`/api/admin/billing/payments${status ? `?status=${status}` : ''}`, {}, true),
  approvePayment: (id: string) => request<void>(`/api/admin/billing/payments/${id}/approve`, { method: 'POST' }, true),
  rejectPayment: (id: string, note?: string) =>
    request<void>(`/api/admin/billing/payments/${id}/reject`, { method: 'POST', body: json({ note: note ?? null }) }, true),
  studioBilling: () => request<StudioBilling[]>('/api/admin/billing/studios', {}, true),
  updateStudioBilling: (studioId: string, input: { plan?: Plan; monthlyQuota?: number; addCredits?: number }) =>
    request<StudioBilling>(`/api/admin/billing/studios/${studioId}`, { method: 'PATCH', body: json(input) }, true),
  billingSettings: () => request<BillingSettings>('/api/admin/billing/settings', {}, true),
  updateBillingSettings: (s: BillingSettings) =>
    request<BillingSettings>('/api/admin/billing/settings', { method: 'PUT', body: json(s) }, true),
  account: () => request<Account>('/api/admin/account', {}, true),
  /** Returns a fresh login for this device: the change logs out every other device. */
  changePassword: (currentPassword: string, newPassword: string) =>
    request<{ token: string; expiresAt: string } | undefined>('/api/admin/account/password', { method: 'POST', body: json({ currentPassword, newPassword }) }, true),
  studios: () => request<Studio[]>('/api/admin/studios', {}, true),
  createStudio: (input: { name: string; email: string; password: string; plan?: Plan }) =>
    request<Studio>('/api/admin/studios', { method: 'POST', body: json(input) }, true),
  updateStudio: (id: string, input: { name?: string; disabled?: boolean; newPassword?: string }) =>
    request<Studio>(`/api/admin/studios/${id}`, { method: 'PATCH', body: json(input) }, true),
  deleteUploader: (uploaderId: string) => request<void>(`/api/admin/uploaders/${uploaderId}`, { method: 'DELETE' }, true),
}
