export type EventStatus = 'DRAFT' | 'UPCOMING' | 'LIVE' | 'COMPLETED' | 'ARCHIVED'
export type PhotoStatus = 'QUEUED' | 'UPLOADING' | 'UPLOADED' | 'PROCESSING' | 'READY' | 'FAILED'
export type UploaderStatus = 'ONLINE' | 'OFFLINE' | 'ERROR'

// ---- Public (guest) ----

/** Whose brand guests see on an event: McreatiK's built-in design, or the owning studio's name, logo and Instagram. */
export interface Brand {
  name: string
  logoUrl: string | null
  instagram: string | null
  mcreatik: boolean
  /** Poster logo size in percent of the standard size (50–200). */
  logoScale?: number
}

export interface PublicEvent {
  name: string
  slug: string
  eventDate: string
  status: EventStatus
  live: boolean
  coverUrl: string | null
  photoCount: number
  brand?: Brand
}

export interface PublicPhoto {
  id: string
  thumbnailUrl: string
  webUrl: string
  width: number | null
  height: number | null
  readyAt: string
  cursor: string
}

export interface PublicPhotoPage {
  photos: PublicPhoto[]
  nextCursor: string | null
}

/** SSE payload of PHOTO_READY. */
export interface PhotoReadyMessage {
  eventId: string
  photoId: string
  thumbnailUrl: string
  webUrl: string
  width: number | null
  height: number | null
  readyAt: string
  cursor: string
}

// ---- Admin ----

export type AdminRole = 'SUPER_ADMIN' | 'STUDIO'

export interface LoginResponse {
  token: string
  expiresAt: string
  email: string
  displayName: string
  role: AdminRole
  /** The super admin set this password: the studio must choose its own first. */
  mustChangePassword: boolean
}

export interface Account {
  email: string
  displayName: string
  role: AdminRole
  mustChangePassword: boolean
  brand?: Brand
}

/** A page of McreatiK's internal documentation (super admin only). */
export interface DocPageSummary {
  slug: string
  title: string
  group: string
}

export interface DocPage extends DocPageSummary {
  markdown: string
  reviewedOn: string
}

/** A login on the super admin's Studios page (McreatiK itself is listed too, as SUPER_ADMIN). */
export interface Studio {
  id: string
  name: string
  email: string
  role: AdminRole
  disabled: boolean
  mustChangePassword: boolean
  createdAt: string
  events: number
  photos: number
  storageBytes: number
  /** What guests see on this studio's galleries and posters. */
  brand?: Brand
}

export interface EventSummary {
  id: string
  name: string
  slug: string
  eventDate: string
  status: EventStatus
  galleryUrl: string
  readyPhotos: number
  createdAt: string
  ownerId: string
  /** The studio that owns the event (shown to the super admin). */
  ownerName: string | null
}

export interface EventDetail {
  id: string
  name: string
  slug: string
  eventDate: string
  startTime: string | null
  endTime: string | null
  status: EventStatus
  coverPhotoId: string | null
  coverUrl: string | null
  galleryUrl: string
  retentionUntil: string | null
  createdAt: string
  updatedAt: string
  ownerId: string
  ownerName: string | null
  brand?: Brand
  /** McreatiK (super admin) can turn these off; the studio only sees them. */
  liveGalleryEnabled?: boolean
  clientGalleryEnabled?: boolean
}

export interface UploaderStats {
  id: string
  name: string
  status: UploaderStatus
  lastSeenAt: string | null
  deviceIdentifier: string | null
  tokenPrefix: string
  photosUploaded: number
  photosReady: number
  queuePending: number
  queueFailed: number
  lastError: string | null
  lastUploadAt: string | null
  /** Privacy pause: the camera's new photos stay on the laptop and none reach the gallery. */
  paused: boolean
}

export interface AdminPhoto {
  id: string
  uploaderId: string | null
  originalFileName: string
  status: PhotoStatus
  failureReason: string | null
  fileSize: number
  width: number | null
  height: number | null
  thumbnailUrl: string | null
  webUrl: string | null
  capturedAt: string | null
  uploadedAt: string | null
  readyAt: string | null
  createdAt: string
  /** False when the uploader prepared the web/thumbnail versions and the original stayed on the laptop. */
  originalAvailable: boolean
  /** Kept in the dashboard but not shown to guests. */
  hidden: boolean
  /** The photographer's pick. */
  liked: boolean
  /** The studio's category (Couple, Cake cutting…); null = not sorted. */
  categoryId: string | null
  /** The couple selected it for the album. */
  selected: boolean
}

export interface AdminPhotoPage {
  photos: AdminPhoto[]
  /** Pass back as `offset` for the next page; null when there are no more. */
  nextOffset: number | null
}

export type AdminPhotoSort = 'NEWEST' | 'OLDEST' | 'CAMERA'

export interface AdminPhotoQuery {
  sort?: AdminPhotoSort
  uploaderId?: string
  liked?: boolean
  hidden?: boolean
  /** yyyy-mm-dd, the day the photo was taken */
  day?: string
  /** A category id, or "none" for photos not sorted yet. */
  category?: string
  /** Only photos the couple selected for the album. */
  selected?: boolean
  offset?: number
  limit?: number
}

export interface PhotoFacets {
  days: { day: string; photos: number }[]
  liked: number
  hidden: number
}

export interface EventStats {
  totalPhotos: number
  readyPhotos: number
  inProgressPhotos: number
  failedPhotos: number
  storageBytes: number
  activeUploaders: number
  latestPhoto: AdminPhoto | null
  uploaders: UploaderStats[]
  hiddenPhotos: number
  likedPhotos: number
}

export interface UploaderCredentials {
  uploaderId: string
  name: string
  token: string
  connectionCode: string
}

/** Anonymous guest activity on an event's gallery (dashboard analytics). */
export interface GuestAnalytics {
  totals: {
    people: number
    qrScans: number
    visits: number
    photoViews: number
    downloads: number
    downloaders: number
    shares: number
    instagramClicks: number
  }
  /** Hours with activity, India time, e.g. "2026-10-31T19:00". */
  hours: { hour: string; people: number; photoViews: number; downloads: number }[]
  topPhotos: { photoId: string; thumbnailUrl: string | null; fileName: string; views: number; downloads: number }[]
  lastActivityAt: string | null
}

/** Where the browser PUTs a full-quality original (see adminApi.originalUpload). */
export interface OriginalUploadSession {
  outcome: 'UPLOAD' | 'DUPLICATE'
  photoId: string
  upload: { url: string; method: string; headers: Record<string, string>; expiresAt: string } | null
  /** False when the photo is already in the gallery and only needs its original. */
  needsVersions: boolean
}

/** A photo category of an event (Couple, Family & Groups, …), with how many photos it holds. */
export interface Category {
  id: string
  name: string
  position: number
  photos: number
}

/** The couple's login to their event's photos. */
export interface EventClient {
  id: string
  name: string
  email: string
  /** WhatsApp number, to send the login again. */
  phone?: string | null
  mustChangePassword: boolean
  disabled: boolean
  createdAt: string
  lastLoginAt: string | null
}

/** A ZIP of several photos, ready to open (the link works for 30 minutes). */
export interface PreparedZip {
  path: string
  /** Polled for the percentage shown in the app. */
  progressPath: string
  photos: number
  fileName: string
}

/** WAITING: link not opened yet; QUEUED: other downloads first; RUNNING; DONE; FAILED (stopped). */
export interface ZipProgress {
  state: 'WAITING' | 'QUEUED' | 'RUNNING' | 'DONE' | 'FAILED'
  done: number
  total: number
  percent: number
}

export interface AlbumSelection {
  /** Max photos the couple may select; null = no limit. */
  limit: number | null
  selected: { photoId: string; fileName: string; thumbnailUrl: string | null; selectedAt: string; selectedBy: string | null; categoryId: string | null }[]
}

export type Plan = 'MONTHLY' | 'PER_EVENT' | 'UNLIMITED'

export interface EventPayment {
  id: string
  studioId: string
  studioName: string
  events: number
  unitPrice: number
  amount: number
  /** UPI transaction ID (UTR) the studio reported (UPI QR payments only). */
  utr: string | null
  status: 'PENDING' | 'APPROVED' | 'REJECTED'
  note: string | null
  createdAt: string
  reviewedAt: string | null
  /** UPI_QR: checked by McreatiK. RAZORPAY: paid online. UPI_AUTO: Razorpay UPI QR. Both confirmed by Razorpay. */
  method: 'UPI_QR' | 'RAZORPAY' | 'UPI_AUTO'
  razorpayPaymentId: string | null
}

/** A Razorpay order the server opened for "Pay online" (the amount is always the server's). */
export interface OnlineOrder {
  paymentId: string
  razorpayOrderId: string
  keyId: string
  amountPaise: number
  currency: 'INR'
  events: number
  amount: number
  studioName: string
  email: string
}

export interface OnlinePaymentResult {
  paymentId: string
  events: number
  /** Bought events now available. */
  credits: number
}

/** The logged-in studio's plan and allowance. */
export interface BillingSummary {
  plan: Plan
  /** McreatiK itself or an unlimited studio: never asked to pay. */
  free: boolean
  monthlyQuota: number
  usedThisMonth: number
  /** Bought (or given) events left; carry over month to month. */
  credits: number
  /** What one more event costs on this plan. */
  unitPrice: number
  monthlyPrice: number
  upiId: string | null
  payeeName: string | null
  payments: EventPayment[]
  /** Card / netbanking / UPI / wallet checkout (Razorpay) is set up. */
  onlinePayments: boolean
}

export interface StudioBilling {
  studioId: string
  plan: Plan
  monthlyQuota: number
  usedThisMonth: number
  credits: number
}

export interface BillingSettings {
  upiId: string | null
  payeeName: string | null
  perEventPrice: number
  extraEventPrice: number
  monthlyPrice: number
}

/** Current prices for the public /pricing page. */
export interface PublicPricing {
  perEventPrice: number
  monthlyPrice: number
  monthlyEvents: number
  extraEventPrice: number
  currency: 'INR'
}

/** A single-use UPI QR for the exact amount, confirmed automatically (no transaction ID to type). */
export interface UpiQrOrder {
  paymentId: string
  /** upi://pay link drawn as the QR, or opened by a UPI app on this phone. */
  upiLink: string | null
  imageUrl: string | null
  events: number
  amount: number
  expiresAt: string
}

export interface OnlinePaymentStatus {
  paymentId: string
  status: 'WAITING' | 'PAID' | 'EXPIRED'
  events: number
  credits: number
}
