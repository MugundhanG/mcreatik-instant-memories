export type EventStatus = 'DRAFT' | 'UPCOMING' | 'LIVE' | 'COMPLETED' | 'ARCHIVED'
export type PhotoStatus = 'QUEUED' | 'UPLOADING' | 'UPLOADED' | 'PROCESSING' | 'READY' | 'FAILED'
export type UploaderStatus = 'ONLINE' | 'OFFLINE' | 'ERROR'

// ---- Public (guest) ----

export interface PublicEvent {
  name: string
  slug: string
  eventDate: string
  status: EventStatus
  live: boolean
  coverUrl: string | null
  photoCount: number
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

export interface LoginResponse {
  token: string
  expiresAt: string
  email: string
  displayName: string
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
}

export interface AdminPhotoPage {
  photos: AdminPhoto[]
  nextCursor: string | null
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
}

export interface UploaderCredentials {
  uploaderId: string
  name: string
  token: string
  connectionCode: string
}
