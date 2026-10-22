import { useState } from "react";
import type { Findings, Qa, Ranked, Summary, Tests } from "../types";
import { StatusBadge } from "./StatusBadge";

const fmt = new Intl.NumberFormat();
const pct = (v: number) => `${(100 * v).toFixed(1)}%`;

function Tile({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <div className="tile">
      <div className="tile-label">{label}</div>
      <div className="tile-value">{value}</div>
      {sub && <div className="tile-sub">{sub}</div>}
    </div>
  );
}

/** Horizontal bars: ambiguous events per predicted class (single series, direct-labelled, hover detail). */
function ClassChart({ findings }: { findings: Findings }) {
  const [hover, setHover] = useState<string | null>(null);
  const [asTable, setAsTable] = useState(false);
  const rows = findings.class_uncertainty.filter((c) => c.ambiguous > 0).slice(0, 10);
  const max = Math.max(1, ...rows.map((r) => r.ambiguous));
  return (
    <figure className="chart">
      <figcaption className="chart-head">
        <span>Ambiguous events by predicted class <span className="muted">({findings.ambiguous_definition})</span></span>
        <button className="link small" onClick={() => setAsTable(!asTable)}>{asTable ? "Show chart" : "Show table"}</button>
      </figcaption>
      {asTable ? (
        <table className="data small">
          <thead><tr><th>Class</th><th>Ambiguous</th><th>Events</th><th>Share</th><th>Mean margin</th></tr></thead>
          <tbody>
            {findings.class_uncertainty.map((c) => (
              <tr key={c.class}><td>{c.class}</td><td>{c.ambiguous}</td><td>{c.events}</td><td>{pct(c.ambiguous_share)}</td><td>{c.mean_margin.toFixed(3)}</td></tr>
            ))}
          </tbody>
        </table>
      ) : (
        <div className="bars" role="list">
          {rows.map((r) => (
            <div
              key={r.class}
              role="listitem"
              tabIndex={0}
              className={`bar-row ${hover === r.class ? "hover" : ""}`}
              onPointerEnter={() => setHover(r.class)}
              onPointerLeave={() => setHover(null)}
              onFocus={() => setHover(r.class)}
              onBlur={() => setHover(null)}
            >
              <span className="bar-label">{r.class}</span>
              <span className="bar-track">
                <span className="bar" style={{ width: `${(100 * r.ambiguous) / max}%` }} />
                <span className="bar-value">{r.ambiguous}</span>
              </span>
              {hover === r.class && (
                <span className="tooltip" role="status">
                  <strong>{r.ambiguous}</strong> of {fmt.format(r.events)} {r.class} events ambiguous ({pct(r.ambiguous_share)}) · mean margin {r.mean_margin.toFixed(2)}
                </span>
              )}
            </div>
          ))}
        </div>
      )}
    </figure>
  );
}

export function ResearchSummary({ summary, findings, tests, qa, ranked }: {
  summary?: Summary;
  findings: Findings | null;
  tests: Tests | null;
  qa: Qa | null;
  ranked: Ranked | null;
}) {
  if (!findings) {
    return (
      <section className="panel summary">
        <h2>Research summary</h2>
        <p className="muted">Results appear once the Developer freezes the ranked output.</p>
      </section>
    );
  }
  const stab = findings.rank_stability;
  return (
    <section className="panel summary">
      <div className="panel-head">
        <h2>Research summary</h2>
        {summary && <span className="muted small">by {summary.written_by}</span>}
      </div>
      {summary && <p className="headline">{summary.headline}</p>}
      <div className="tiles">
        <Tile label="Events checked" value={fmt.format(findings.events_checked)} sub={ranked?.run_id.split("_").slice(1).join(" ").replace("_", " · ") || undefined} />
        <Tile label="Ambiguous events" value={fmt.format(findings.ambiguous_events)} sub={pct(findings.ambiguous_share) + " of events"} />
        <Tile label="Most common class" value={findings.most_common_class.replaceAll("_", " ")} />
        <Tile label="Label conflicts" value={fmt.format(findings.label_conflicts.count)} sub="ml_label ≠ argmax" />
        {stab && <Tile label="Bootstrap" value={`${fmt.format(stab.resamples)}×`} sub={`${stab.seconds}s on ${stab.threads} threads`} />}
      </div>
      <div className="summary-cols">
        <ClassChart findings={findings} />
        <div>
          <h3>Most frequent competing pairs</h3>
          <ol className="pairs small">
            {findings.confusion_pairs.slice(0, 5).map((p) => (
              <li key={p.pair}><span>{p.pair.replace(" <> ", " ↔ ")}</span><strong>{p.ambiguous_events}</strong></li>
            ))}
          </ol>
          <h3>Quality</h3>
          <div className="quality small">
            {tests && <div><StatusBadge tone={tests.verdict === "pass" ? "good" : "critical"} label={`Tests ${tests.passed}/${tests.total}`} /></div>}
            {qa?.checks.map((c) => (
              <div key={c.check} className="qa-row">
                <StatusBadge tone={c.status === "pass" ? "good" : c.status === "warn" ? "warning" : "critical"} label={c.check.replaceAll("_", " ")} />
                {c.status !== "pass" && <span className="muted">{c.detail}</span>}
              </div>
            ))}
          </div>
        </div>
      </div>
      {summary && summary.next_steps.length > 0 && (
        <>
          <h3>Investigate next</h3>
          <ol className="next small">{summary.next_steps.map((s, i) => <li key={i}>{s}</li>)}</ol>
        </>
      )}
      {ranked && (
        <details className="formula small">
          <summary>Ranking formula ({ranked.spec.author})</summary>
          <code>{ranked.spec.formula}</code>
          <p>Weights: entropy {ranked.spec.weights.entropy}, margin {ranked.spec.weights.margin}, outlier {ranked.spec.weights.outlier}</p>
          <p>{ranked.spec.rationale}</p>
          <ul>{ranked.spec.limitations.map((l, i) => <li key={i}>{l}</li>)}</ul>
        </details>
      )}
    </section>
  );
}
