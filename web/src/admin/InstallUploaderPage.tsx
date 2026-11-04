import { Link } from 'react-router'
import { DownloadIcon } from './icons'
import { detectPlatform, UPLOADER_DOWNLOADS } from './uploaderDownloads'

function DownloadButton({ href, children, primary }: { href: string; children: React.ReactNode; primary?: boolean }) {
  return (
    <a href={href}
      className={`inline-flex h-10 items-center justify-center gap-2 rounded-lg px-4 text-sm font-semibold transition-colors ${
        primary ? 'bg-ink text-white hover:bg-stone-700' : 'border border-line bg-white text-ink hover:bg-stone-50'}`}>
      <DownloadIcon className="size-4" />
      {children}
    </a>
  )
}

function WindowsLogo() {
  return (
    <svg viewBox="0 0 24 24" className="size-7" aria-hidden="true">
      <path fill="currentColor" d="M3 5.5 10.5 4.5v7H3zm8.5-1.1L21 3v8.5h-9.5zM3 12.5h7.5v7L3 18.5zm8.5 0H21V21l-9.5-1.4z" />
    </svg>
  )
}

function AppleLogo() {
  return (
    <svg viewBox="0 0 24 24" className="size-7" aria-hidden="true">
      <path fill="currentColor" d="M16.4 12.6c0-2.4 2-3.6 2.1-3.7-1.1-1.7-2.9-1.9-3.5-1.9-1.5-.2-2.9.9-3.7.9-.8 0-1.9-.9-3.2-.8-1.6 0-3.1 1-4 2.4-1.7 3-.4 7.4 1.2 9.8.8 1.2 1.8 2.5 3 2.4 1.2 0 1.7-.8 3.2-.8s1.9.8 3.2.8c1.3 0 2.2-1.2 3-2.4.9-1.4 1.3-2.7 1.3-2.8-.1 0-2.6-1-2.6-3.9zM14 5.5c.7-.8 1.1-1.9 1-3-1 0-2.1.7-2.8 1.5-.6.7-1.2 1.8-1 2.9 1.1.1 2.1-.6 2.8-1.4z" />
    </svg>
  )
}

/** "Install uploader": the desktop app for every studio, Windows and Mac, with the steps after installing. */
export default function InstallUploaderPage() {
  const platform = detectPlatform()
  const windowsFirst = platform !== 'mac'
  const windows = (
    <section key="windows" className={`rounded-2xl border bg-white p-6 ${platform === 'windows' ? 'border-ink' : 'border-line'}`}>
      <div className="flex items-center gap-3">
        <WindowsLogo />
        <div>
          <h2 className="text-lg font-semibold">Windows</h2>
          <p className="text-xs text-muted">Windows 10 or 11 · about 50 MB</p>
        </div>
        {platform === 'windows' && <span className="ml-auto rounded-full bg-green-50 px-2.5 py-1 text-xs font-medium text-green-800">This computer</span>}
      </div>
      <div className="mt-5">
        <DownloadButton href={UPLOADER_DOWNLOADS.windows.href} primary={platform === 'windows'}>Download for Windows</DownloadButton>
      </div>
      <ol className="mt-5 list-decimal space-y-1.5 pl-5 text-sm text-stone-700">
        <li>Open the downloaded <code className="text-xs">.msi</code> file.</li>
        <li>If Windows says “Windows protected your PC”, click <b>More info → Run anyway</b>.</li>
        <li>Click through the installer. The app appears in the Start menu and on the desktop.</li>
        <li>When the firewall asks, tick <b>Private and Public</b> networks and click <b>Allow</b>.</li>
      </ol>
    </section>
  )
  const mac = (
    <section key="mac" className={`rounded-2xl border bg-white p-6 ${platform === 'mac' ? 'border-ink' : 'border-line'}`}>
      <div className="flex items-center gap-3">
        <AppleLogo />
        <div>
          <h2 className="text-lg font-semibold">Mac</h2>
          <p className="text-xs text-muted">macOS 12 or newer · about 52 MB</p>
        </div>
        {platform === 'mac' && <span className="ml-auto rounded-full bg-green-50 px-2.5 py-1 text-xs font-medium text-green-800">This computer</span>}
      </div>
      <div className="mt-5 flex flex-wrap gap-2">
        <DownloadButton href={UPLOADER_DOWNLOADS.macApple.href} primary={platform === 'mac'}>Apple chip (M1–M4)</DownloadButton>
        <DownloadButton href={UPLOADER_DOWNLOADS.macIntel.href}>Intel</DownloadButton>
      </div>
      <p className="mt-2 text-xs text-muted">
        Which Mac? Apple menu → <b>About This Mac</b>: “Chip Apple M…” means Apple chip; “Processor Intel” means Intel.
      </p>
      <ol className="mt-4 list-decimal space-y-1.5 pl-5 text-sm text-stone-700">
        <li>Open the <code className="text-xs">.dmg</code> file and drag the app into <b>Applications</b>.</li>
        <li>Open it. The first time, macOS can't check it: click <b>Done</b>, then <b>System Settings → Privacy &amp; Security → Open Anyway</b>.</li>
        <li>When asked about incoming network connections, click <b>Allow</b>.</li>
      </ol>
    </section>
  )
  return (
    <div className="mx-auto max-w-4xl">
      <p className="text-xs font-semibold tracking-[0.2em] text-[#8a5a1c] uppercase">Desktop app</p>
      <h1 className="mt-1 font-display text-3xl font-semibold">McreatiK Instant Memories Uploader</h1>
      <p className="mt-2 max-w-2xl text-sm text-muted">
        Install it on the laptop you take to the event. It receives every photo from the camera over Wi-Fi and puts it in the
        live gallery within seconds. Java is included, so there is nothing else to install. One laptop can serve up to 4 cameras.
      </p>
      {platform === 'phone' && (
        <p className="mt-4 rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900">
          This is a phone. Open this page on the Windows or Mac laptop you'll use at the venue, or send yourself the link.
        </p>
      )}
      <div className="mt-6 grid gap-4 md:grid-cols-2">{windowsFirst ? [windows, mac] : [mac, windows]}</div>
      <section className="mt-6 rounded-2xl border border-line bg-white p-6">
        <h2 className="text-base font-semibold">After installing</h2>
        <ol className="mt-3 list-decimal space-y-1.5 pl-5 text-sm text-stone-700">
          <li>Open the event here: <b>Overview → Cameras &amp; uploaders → Add uploader → Copy code</b>.</li>
          <li>Open the uploader, press <b>Paste</b>, then <b>Connect</b>.</li>
          <li>Type the <b>Server, Port, User and Password</b> it shows into the camera's FTP settings, and take a test shot.</li>
          <li>More cameras on the same laptop: press <b>+ Add camera</b> in the uploader and use the port it shows.</li>
        </ol>
        <p className="mt-4 text-xs text-muted">
          Already installed? Installing again updates it; your settings and queued photos are kept.{' '}
          <Link to="/admin" className="font-medium text-ink underline underline-offset-2">Back to events</Link>
        </p>
      </section>
    </div>
  )
}
