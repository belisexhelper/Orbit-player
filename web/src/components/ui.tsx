import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react';

// ---------- Obaveštenja (toast) ----------

type Toast = { text: string; bad?: boolean } | null;
const ToastCtx = createContext<(text: string, bad?: boolean) => void>(() => undefined);

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toast, setToast] = useState<Toast>(null);
  const timer = useRef<number | undefined>(undefined);
  const show = useCallback((text: string, bad = false) => {
    setToast({ text, bad });
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => setToast(null), 3500);
  }, []);
  return (
    <ToastCtx.Provider value={show}>
      {children}
      {toast && (
        <div className={`toast${toast.bad ? ' bad' : ''}`} role="status">
          {toast.text}
        </div>
      )}
    </ToastCtx.Provider>
  );
}

export const useToast = () => useContext(ToastCtx);

// ---------- Modal ----------

export function Modal({ title, onClose, children, wide }: { title: string; onClose: () => void; children: ReactNode; wide?: boolean }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);
  return (
    <div className="modal-back" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal" role="dialog" aria-modal="true" aria-label={title} style={wide ? { maxWidth: 760 } : undefined}>
        <h2>{title}</h2>
        {children}
      </div>
    </div>
  );
}

export function Confirm({ text, confirmLabel, danger, onConfirm, onClose }: { text: string; confirmLabel: string; danger?: boolean; onConfirm: () => Promise<void> | void; onClose: () => void }) {
  const [busy, setBusy] = useState(false);
  return (
    <Modal title="Potvrda" onClose={onClose}>
      <p>{text}</p>
      <div className="actions">
        <button className="btn secondary" onClick={onClose}>
          Odustani
        </button>
        <button
          className={`btn${danger ? ' danger' : ''}`}
          disabled={busy}
          onClick={async () => {
            setBusy(true);
            try {
              await onConfirm();
              onClose();
            } finally {
              setBusy(false);
            }
          }}
        >
          {confirmLabel}
        </button>
      </div>
    </Modal>
  );
}

// ---------- Unos koda (8 cifara) ----------

export function CodeInput({ value, onChange, onComplete, autoFocus }: { value: string; onChange: (v: string) => void; onComplete?: (v: string) => void; autoFocus?: boolean }) {
  const refs = useRef<(HTMLInputElement | null)[]>([]);
  // vrednost je niz od 8 znakova, prazno mesto je razmak
  const digits = Array.from({ length: 8 }, (_, i) => (value[i] ?? '').trim());
  const join = (arr: string[]) => arr.map((d) => d || ' ').join('');

  const setAt = (i: number, d: string) => {
    const arr = digits.slice();
    arr[i] = d;
    const next = join(arr);
    onChange(next);
    if (/^\d{8}$/.test(next) && onComplete) onComplete(next);
  };

  const fill = (raw: string, start: number) => {
    const clean = raw.replace(/\D/g, '');
    if (!clean) return;
    const arr = digits.slice();
    for (let k = 0; k < clean.length && start + k < 8; k++) arr[start + k] = clean[k]!;
    const next = join(arr);
    onChange(next);
    const focusAt = Math.min(7, start + clean.length);
    refs.current[focusAt]?.focus();
    if (/^\d{8}$/.test(next) && onComplete) onComplete(next);
  };

  return (
    <div className="code-input" aria-label="Kod sa televizora, 8 cifara">
      {digits.map((d, i) => (
        <FragmentDigit key={i} gap={i === 4}>
          <input
            ref={(el) => {
              refs.current[i] = el;
            }}
            inputMode="numeric"
            autoComplete="one-time-code"
            aria-label={`Cifra ${i + 1}`}
            placeholder="0"
            value={d}
            autoFocus={autoFocus && i === 0}
            onFocus={(e) => e.target.select()}
            onPaste={(e) => {
              e.preventDefault();
              fill(e.clipboardData.getData('text'), i);
            }}
            onChange={(e) => {
              const v = e.target.value.replace(/\D/g, '');
              if (v.length > 1) return fill(v, i);
              setAt(i, v);
              if (v && i < 7) refs.current[i + 1]?.focus();
            }}
            onKeyDown={(e) => {
              if (e.key === 'Backspace' && !digits[i] && i > 0) {
                refs.current[i - 1]?.focus();
                setAt(i - 1, '');
              }
              if (e.key === 'ArrowLeft' && i > 0) refs.current[i - 1]?.focus();
              if (e.key === 'ArrowRight' && i < 7) refs.current[i + 1]?.focus();
            }}
          />
        </FragmentDigit>
      ))}
    </div>
  );
}

function FragmentDigit({ gap, children }: { gap: boolean; children: ReactNode }) {
  return (
    <>
      {gap && <span className="gap" aria-hidden="true" />}
      {children}
    </>
  );
}
