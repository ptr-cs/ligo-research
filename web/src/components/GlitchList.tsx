import { Fragment, useState } from "react";
import type { Ranked, RankedEvent } from "../types";

const hasImage = (url: string) => url.startsWith("http");

function Spectrogram({ e, window: w, className }: { e: RankedEvent; window: number; className?: string }) {
  const s = e.spectrograms[w];
  if (!s || !hasImage(s.url)) return <div className={`spec-missing ${className ?? ""}`}>No spectrogram published</div>;
  return <img className={className} src={s.url} alt={`${e.gravityspy_id} spectrogram, ${s.window} window`} loading="lazy" />;
}

/** Top classes as thin bars with direct labels; the predicted label is marked in text, not color. */
function ClassBars({ e }: { e: RankedEvent }) {
  return (
    <div className="prob-bars">
      {e.top_classes.map((c) => (
        <div key={c.class} className="prob-row">
          <span className="prob-label">
            {c.class}
            {c.class === e.ml_label && <span className="tag">predicted</span>}
          </span>
          <span className="bar-track">
            <span className="bar" style={{ width: `${100 * c.probability}%` }} />
            <span className="bar-value">{c.probability.toFixed(2)}</span>
          </span>
        </div>
      ))}
    </div>
  );
}

function Detail({ e }: { e: RankedEvent }) {
  const pc = e.scores.percentiles;
  return (
    <div className="detail">
      <div className="detail-specs">
        {e.spectrograms.map((s, i) => (
          <figure key={s.window}>
            <Spectrogram e={e} window={i} />
            <figcaption className="small muted">{s.window}</figcaption>
          </figure>
        ))}
      </div>
      <div className="detail-cols">
        <div>
          <h4>Why it is ranked #{e.rank}</h4>
          <p>{e.explanation}</p>
          <table className="data small">
            <thead><tr><th>Signal</th><th>Value</th><th>Percentile</th><th>Contribution</th></tr></thead>
            <tbody>
              <tr><td>Entropy (norm.)</td><td>{e.scores.entropy_norm.toFixed(3)}</td><td>higher than {(100 * pc.entropy_higher_than).toFixed(1)}%</td><td>{e.scores.contributions.entropy.toFixed(3)}</td></tr>
              <tr><td>Top-two margin</td><td>{e.scores.margin.toFixed(3)}</td><td>closer than {(100 * pc.margin_closer_than).toFixed(1)}%</td><td>{e.scores.contributions.margin.toFixed(3)}</td></tr>
              <tr><td>Metadata outlier (RMS z)</td><td>{e.scores.outlier_rms_z.toFixed(2)}</td><td>higher than {(100 * pc.outlier_higher_than).toFixed(1)}%</td><td>{e.scores.contributions.outlier.toFixed(3)}</td></tr>
              <tr><td>Label/argmax conflict</td><td>{e.label_conflict ? "yes" : "no"}</td><td>—</td><td>{e.scores.contributions.label_conflict.toFixed(3)}</td></tr>
              <tr><th>Priority</th><th>{e.scores.priority.toFixed(3)}</th><th /><th /></tr>
            </tbody>
          </table>
        </div>
        <div>
          <h4>Competing classifications</h4>
          <ClassBars e={e} />
          <h4>Metadata vs {e.baseline.replace("class:", "")} baseline</h4>
          <table className="data small">
            <thead><tr><th>Feature</th><th>Value</th><th>Baseline median</th><th>Robust z</th></tr></thead>
            <tbody>
              {e.outlier_features.map((f) => (
                <tr key={f.feature}>
                  <td>{f.feature}</td>
                  <td>{f.value.toPrecision(4)}</td>
                  <td>{f.baseline_median.toPrecision(4)}</td>
                  <td className={Math.abs(f.robust_z) >= 3 ? "strong" : ""}>{f.robust_z.toFixed(2)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="small muted">
            GPS {e.event_time} · {e.ifo} · ml_confidence {e.ml_confidence.toFixed(3)}
            {e.stability !== null && <> · bootstrap stability {(100 * e.stability).toFixed(0)}%</>}
          </p>
        </div>
      </div>
    </div>
  );
}

export function GlitchList({ ranked }: { ranked: Ranked | null }) {
  const [open, setOpen] = useState<string | null>(null);
  const [win, setWin] = useState(1);
  if (!ranked) {
    return (
      <section className="panel glitches">
        <h2>Highest-priority glitches</h2>
        <p className="muted">The ranked list appears once the Developer freezes its output.</p>
      </section>
    );
  }
  return (
    <section className="panel glitches">
      <div className="panel-head">
        <h2>Highest-priority glitches <span className="muted small">top {ranked.top_n} of {ranked.events_scored.toLocaleString()}</span></h2>
        <div className="seg small" role="group" aria-label="Spectrogram window">
          {ranked.events[0]?.spectrograms.map((s, i) => (
            <button key={s.window} className={win === i ? "active" : ""} onClick={() => setWin(i)}>{s.window}</button>
          ))}
        </div>
      </div>
      <div className="table-wrap">
        <table className="glitch-table">
          <thead>
            <tr><th>#</th><th>Spectrogram</th><th>Predicted</th><th>Alternative</th><th>Priority</th><th>Why</th></tr>
          </thead>
          <tbody>
            {ranked.events.map((e) => {
              const alt = e.top_classes.find((c) => c.class !== e.ml_label) ?? e.top_classes[1];
              const isOpen = open === e.gravityspy_id;
              return (
                <Fragment key={e.gravityspy_id}>
                  <tr className={`glitch-row ${isOpen ? "open" : ""}`} onClick={() => setOpen(isOpen ? null : e.gravityspy_id)}>
                    <td className="rank">{e.rank}</td>
                    <td><Spectrogram e={e} window={win} className="thumb" /></td>
                    <td>
                      <div className="strong">{e.ml_label}</div>
                      <div className="small muted">{e.ml_confidence.toFixed(2)} · <code>{e.gravityspy_id}</code></div>
                      {e.label_conflict && <div className="small warn">! argmax is {e.argmax_label}</div>}
                    </td>
                    <td>
                      <div>{alt.class}</div>
                      <div className="small muted">{alt.probability.toFixed(2)}</div>
                    </td>
                    <td className="num">{e.scores.priority.toFixed(3)}</td>
                    <td className="why">
                      <div className="drivers">{e.drivers.map((d) => <span key={d} className="driver">{d}</span>)}</div>
                      <div className="small">{e.explanation.split(". ").slice(1, 3).join(". ")}.</div>
                      <button className="link small">{isOpen ? "Hide detail" : "Show detail"}</button>
                    </td>
                  </tr>
                  {isOpen && (
                    <tr className="detail-row">
                      <td colSpan={6}><Detail e={e} /></td>
                    </tr>
                  )}
                </Fragment>
              );
            })}
          </tbody>
        </table>
      </div>
    </section>
  );
}
