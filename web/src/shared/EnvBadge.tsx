/** Small amber badge shown only on non-production builds (VITE_ENV_LABEL, e.g. "beta"). Renders nothing when unset. */
export default function EnvBadge({ label = import.meta.env.VITE_ENV_LABEL, corner = false }: { label?: string; corner?: boolean }) {
  const text = label?.trim()
  if (!text) return null
  const badge = (
    <span
      data-testid="env-badge"
      title={`This is the ${text} environment, not the live site`}
      className="rounded-full bg-amber-400 px-2 py-0.5 text-[10px] font-semibold tracking-widest text-amber-950 uppercase"
    >
      {text}
    </span>
  )
  if (!corner) return badge
  return <div className="pointer-events-none fixed bottom-2 left-2 z-40 opacity-80">{badge}</div>
}
