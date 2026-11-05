import { useState, type InputHTMLAttributes } from 'react'

/**
 * A password box with an eye button to show or hide what was typed (fewer failed logins on phones).
 * Drop-in for `<input type="password">`: takes the same props; `wrapperClassName` goes on the outer box
 * (use it for margins, so the eye stays centred on the field).
 */
export function PasswordInput({ className = '', wrapperClassName = '', ...props }: Omit<InputHTMLAttributes<HTMLInputElement>, 'type'> & { wrapperClassName?: string }) {
  const [shown, setShown] = useState(false)
  return (
    <div className={`relative ${wrapperClassName}`}>
      <input {...props} type={shown ? 'text' : 'password'} className={`${className} pr-11`}
        autoCapitalize="off" autoCorrect="off" spellCheck={false} />
      <button
        type="button"
        onClick={() => setShown((s) => !s)}
        // Keep the cursor in the box while toggling.
        onMouseDown={(e) => e.preventDefault()}
        aria-label={shown ? 'Hide password' : 'Show password'}
        aria-pressed={shown}
        title={shown ? 'Hide password' : 'Show password'}
        className="absolute inset-y-0 right-0 flex w-11 items-center justify-center rounded-r-lg text-muted hover:text-ink focus-visible:outline-2 focus-visible:outline-ink"
      >
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          {shown ? (
            <>
              <path d="M10.7 5.1A10.4 10.4 0 0 1 12 5c6.5 0 10 7 10 7a17.6 17.6 0 0 1-2.4 3.4M6.6 6.6C3.7 8.4 2 12 2 12s3.5 7 10 7a9.7 9.7 0 0 0 5.4-1.6" />
              <path d="M9.9 9.9a3 3 0 0 0 4.2 4.2M2 2l20 20" />
            </>
          ) : (
            <>
              <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z" />
              <circle cx="12" cy="12" r="3" />
            </>
          )}
        </svg>
      </button>
    </div>
  )
}
