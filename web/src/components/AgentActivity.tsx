import type { BusEvent, Run, Stage } from "../types";
import { StatusBadge, type Tone } from "./StatusBadge";

const ROLES: { role: string; duty: string }[] = [
  { role: "Researcher", duty: "Defines the ranking formula and limitations" },
  { role: "Developer", duty: "Ingestion, scoring, bootstrap, frozen output" },
  { role: "Tester", duty: "Entropy, margins, ranking, determinism" },
  { role: "QA", duty: "Read-only checks of frozen output" },
  { role: "Reporter", duty: "Dashboard summary and status answers" },
];

function roleStatus(stages: Stage[]): { tone: Tone; label: string } {
  if (stages.length === 0) return { tone: "pending", label: "idle" };
  if (stages.some((s) => s.status === "running")) return { tone: "running", label: "working" };
  if (stages.some((s) => s.status === "failed")) return { tone: "critical", label: "failed" };
  if (stages.every((s) => s.status === "pending")) return { tone: "pending", label: "waiting" };
  if (stages.some((s) => s.status === "pending")) return { tone: "running", label: "between phases" };
  if (stages.some((s) => s.status === "complete_with_warnings")) return { tone: "warning", label: "complete, with warnings" };
  return { tone: "good", label: "complete" };
}

function time(ts: string) {
  return new Date(ts).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

export function AgentActivity({ run, events, mode, model }: {
  run: Run | null;
  events: BusEvent[];
  mode?: string;
  model: string | null;
}) {
  const feed = events.filter((e) => e.type === "agent" || e.type === "state").slice(-80).reverse();
  return (
    <section className="panel activity">
      <div className="panel-head">
        <h2>Agent activity</h2>
        <span className="muted small">
          {mode === "claude" ? `Claude agents · ${model}` : "Scripted agents (set ANTHROPIC_API_KEY for Claude)"}
        </span>
      </div>
      <ul className="roles">
        {ROLES.map(({ role, duty }) => {
          const stages = run?.stages.filter((s) => s.role === role) ?? [];
          const st = roleStatus(stages);
          const last = [...stages].reverse().find((s) => s.summary);
          const calls = stages.flatMap((s) => s.tool_calls ?? []);
          const warnings = stages.flatMap((s) => s.warnings ?? []);
          return (
            <li key={role} className="role">
              <div className="role-head">
                <strong>{role}</strong>
                <StatusBadge tone={st.tone} label={st.label} />
              </div>
              <div className="muted small">{duty}</div>
              {last?.summary && <p className="role-summary">{last.summary}</p>}
              {calls.length > 0 && (
                <div className="tools small">
                  {calls.map((c, i) => (
                    <span key={i} className={`tool ${c.ok ? "" : "tool-error"}`} title={`${c.ms} ms${c.error ? ` · ${c.error}` : ""}`}>
                      {c.ok ? "" : "✕ "}{c.tool}
                    </span>
                  ))}
                </div>
              )}
              {warnings.map((w, i) => <div key={i} className="warn small">! {w}</div>)}
            </li>
          );
        })}
      </ul>
      <h3 className="feed-title">Live log</h3>
      <ol className="feed">
        {feed.length === 0 && <li className="muted small">Waiting for the team…</li>}
        {feed.map((e) => (
          <li key={e.seq} className={`feed-item feed-${e.data?.kind ?? e.type}`}>
            <span className="feed-time">{time(e.ts)}</span>
            {e.role && <span className="feed-role">{e.role}</span>}
            <span className="feed-text">{e.text}</span>
          </li>
        ))}
      </ol>
    </section>
  );
}
