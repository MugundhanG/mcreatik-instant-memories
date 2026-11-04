/** Installers with Java included, published by the "Uploader installers" workflow under stable names. */
const RELEASES = 'https://github.com/MugundhanG/mcreatik-instant-memories/releases/latest/download'

export const UPLOADER_DOWNLOADS = {
  windows: { label: 'Windows', file: 'McreatiK-Instant-Memories-Uploader-Windows.msi', href: `${RELEASES}/McreatiK-Instant-Memories-Uploader-Windows.msi` },
  macApple: { label: 'Mac (M1–M4)', file: 'McreatiK-Instant-Memories-Uploader-Mac-AppleSilicon.dmg', href: `${RELEASES}/McreatiK-Instant-Memories-Uploader-Mac-AppleSilicon.dmg` },
  macIntel: { label: 'Mac (Intel)', file: 'McreatiK-Instant-Memories-Uploader-Mac-Intel.dmg', href: `${RELEASES}/McreatiK-Instant-Memories-Uploader-Mac-Intel.dmg` },
} as const

export type Platform = 'windows' | 'mac' | 'phone' | 'other'

/** Best guess of this computer, to put its download first. Never hides the other downloads. */
export function detectPlatform(nav: { userAgent: string; platform?: string; userAgentData?: { platform?: string } } = navigator): Platform {
  const p = `${nav.userAgentData?.platform ?? ''} ${nav.platform ?? ''} ${nav.userAgent}`.toLowerCase()
  if (/iphone|ipad|android|mobile/.test(p)) return 'phone'
  if (p.includes('win')) return 'windows'
  if (p.includes('mac')) return 'mac'
  return 'other'
}
