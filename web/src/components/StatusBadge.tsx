export type Tone = "good" | "warning" | "critical" | "running" | "pending";

const ICON: Record<Tone, string> = { good: "✓", warning: "!", critical: "✕", running: "◐", pending: "○" };

/** Status is always carried by icon + label, never by color alone. */
export function StatusBadge({ tone, label }: { tone: Tone; label: string }) {
  return (
    <span className={`badge badge-${tone}`}>
      <span className="badge-icon" aria-hidden="true">{ICON[tone]}</span>
      {label}
    </span>
  );
}
