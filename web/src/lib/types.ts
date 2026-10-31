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
