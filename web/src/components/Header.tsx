import type { AppState, Qa, Stage } from "../types";
import type { LinkStatus } from "../api";
import { StatusBadge, type Tone } from "./StatusBadge";

function stage(state: AppState | null, role: string, phase: string): Stage | undefined {
  return state?.run?.stages.find((s) => s.role === role && s.phase === phase);
}

function stageTone(s: Stage | undefined): Tone {
  switch (s?.status) {
    case "complete": return "good";
    case "complete_with_warnings": return "warning";
    case "failed": return "critical";
    case "running": return "running";
    default: return "pending";
  }
}

export function Header({ state, qa, link, onRun }: {
  state: AppState | null;
  qa: Qa | null;
  link: LinkStatus;
  onRun: () => void;
}) {
  const qaStage = stage(state, "QA", "qa");
  let qaTone = stageTone(qaStage);
  let qaText = "QA";
  if (qaStage?.status !== "running" && qa && state?.run?.outcome.qa) {
    qaTone = qa.status === "passed" ? "good" : qa.status === "failed" ? "critical" : "warning";
    qaText = qa.status === "passed_with_warnings" ? "QA: warnings" : `QA: ${qa.status}`;
  }
  const tests = state?.run?.outcome.tests;
  const chips: { label: string; tone: Tone }[] = [
    { label: "Dataset", tone: stageTone(stage(state, "Developer", "ingest")) },
    { label: "Analysis", tone: stageTone(stage(state, "Developer", "analysis")) },
    { label: tests ? `Tests ${tests.passed}/${tests.total}` : "Tests", tone: stageTone(stage(state, "Tester", "tests")) },
    { label: qaText, tone: qaTone },
  ];
  const run = state?.run;
  return (
    <header className="header">
      <div className="header-title">
        <h1>Gravity Spy Research Team</h1>
        <p className="muted">
          Ranking ambiguous LIGO glitches for human review
          {run && <> · run <code>{run.run_id}</code> · {run.host.cores}-core fog server</>}
        </p>
      </div>
      <div className="header-status">
        <div className="chips">
          {chips.map((c) => <StatusBadge key={c.label} tone={c.tone} label={c.label} />)}
        </div>
        <div className="header-actions">
          <span
            className={`live ${link !== "offline" ? "on" : ""}`}
            title={link === "live" ? "Live event stream" : link === "polling" ? "Event stream unavailable through this proxy; refreshing every 3 s" : "Disconnected"}
          >
            {link === "live" ? "● Live" : link === "polling" ? "● Live (3 s refresh)" : "○ Offline"}
          </span>
          <button className="btn" onClick={onRun} disabled={link === "offline" || state?.running}>
            {state?.running ? "Team working…" : "Run team again"}
          </button>
        </div>
      </div>
    </header>
  );
}
