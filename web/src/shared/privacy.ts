/** Where guests send privacy questions and photo removal requests (McreatiK's Google Workspace inbox). */
export const PRIVACY_EMAIL = 'connect@mcreatik.com'

/** Prefilled email asking to take one photo down: names the gallery and the photo so it can be found at once. */
export function removalRequestLink(eventName: string, photoId?: string) {
  const gallery = window.location.origin + window.location.pathname
  const subject = `Photo removal request: ${eventName}`
  const body = [
    'Hi, please remove this photo from the gallery.',
    '',
    `Gallery: ${gallery}`,
    photoId ? `Photo: ${photoId}` : 'Photo: (please describe it)',
    '',
    'Reason (optional): ',
  ].join('\n')
  return `mailto:${PRIVACY_EMAIL}?subject=${encodeURIComponent(subject)}&body=${encodeURIComponent(body)}`
}
