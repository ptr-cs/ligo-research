import { useEffect, useState } from "react";
import type { Campaign } from "../types";
import { StatusBadge, type Tone } from "./StatusBadge";

const PHASES: Campaign["phase"][] = ["datasets", "comparison", "sensitivity", "complete"];
const pct = (v?: number | null) => (v == null ? "—" : `${(100 * v).toFixed(1)}%`);

function datasetTone(status: string): Tone {
  if (status === "complete") return "good";
  if (status === "complete_with_issues") return "warning";
  if (status === "failed") return "critical";
  if (status === "running") return "running";
  return "pending";
}

function remaining(deadline: string, now: number) {
  const ms = new Date(deadline).getTime() - now;
  if (ms <= 0) return "deadline reached";
  const h = Math.floor(ms / 3_600_000), m = Math.floor((ms % 3_600_000) / 60_000), s = Math.floor((ms % 60_000) / 1000);
  return `${h}h ${String(m).padStart(2, "0")}m ${String(s).padStart(2, "0")}s left`;
}

export function CampaignPanel({ campaign, selectedRun, onSelectRun }: {
  campaign: Campaign;
  selectedRun: string | null;
  onSelectRun: (runId: string | null) => void;
}) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const t = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(t);
  }, []);
  const maxShare = Math.max(0.01, ...campaign.datasets.map((d) => d.ambiguous_share ?? 0));
  const phaseIndex = PHASES.indexOf(campaign.phase);
  const sweeps = Object.entries(campaign.sensitivity ?? {});

  return (
    <section className="panel campaign">
      <div className="panel-head">
        <h2>Fog-server campaign <span className="muted small">all Gravity Spy datasets · {campaign.hours} h budget</span></h2>
        <div className="campaign-clock small">
          <StatusBadge
            tone={campaign.status === "running" ? "running" : campaign.status === "complete" ? "good" : "critical"}
            label={campaign.status === "running" ? remaining(campaign.deadline, now) : `campaign ${campaign.status}`}
          />
        </div>
      </div>
      <ol className="phases small">
        {PHASES.map((p, i) => (
          <li key={p} className={i < phaseIndex || campaign.phase === "complete" ? "done" : i === phaseIndex ? "current" : ""}>
            {i < phaseIndex || campaign.phase === "complete" ? "✓ " : i === phaseIndex ? "◐ " : "○ "}
            {p === "sensitivity" && campaign.sweep_round != null ? `sensitivity sweep (round ${campaign.sweep_round})` : p}
          </li>
        ))}
      </ol>
      {campaign.campaign_summary && <p className="headline">{campaign.campaign_summary.headline}</p>}

      <div className="table-wrap">
        <table className="data small campaign-table">
          <thead>
            <tr><th>Dataset</th><th>Status</th><th>Events</th><th>Ambiguous share</th><th>Top competing pair</th><th>QA</th><th>Tests</th><th /></tr>
          </thead>
          <tbody>
            {campaign.datasets.map((d) => (
              <tr key={d.dataset} className={selectedRun && d.run_id === selectedRun ? "selected" : ""}>
                <td className="strong">{d.dataset}</td>
                <td><StatusBadge tone={datasetTone(d.status)} label={d.status.replaceAll("_", " ")} /></td>
                <td className="num">{d.events?.toLocaleString() ?? "—"}</td>
                <td>
                  {d.ambiguous_share != null ? (
                    <span className="bar-track">
                      <span className="bar" style={{ width: `${(70 * d.ambiguous_share) / maxShare}%` }} />
                      <span className="bar-value">{pct(d.ambiguous_share)}</span>
                    </span>
                  ) : "—"}
                </td>
                <td>{d.top_confusion_pair ? `${d.top_confusion_pair.replace(" <> ", " ↔ ")} (${d.top_confusion_pair_events})` : "—"}</td>
                <td>{d.qa_status?.replaceAll("_", " ") ?? "—"}</td>
                <td>{d.tests ?? "—"}</td>
                <td>
                  {d.run_id && (
                    <button className="link" onClick={() => onSelectRun(d.run_id === selectedRun ? null : d.run_id!)}>
                      {d.run_id === selectedRun ? "Viewing" : "View"}
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {(campaign.campaign_summary?.highlights ?? campaign.comparison?.highlights)?.length ? (
        <>
          <h3>Cross-dataset comparison</h3>
          <ul className="next small">
            {(campaign.campaign_summary?.highlights ?? campaign.comparison!.highlights).map((h, i) => <li key={i}>{h}</li>)}
          </ul>
          {campaign.campaign_summary?.caveats?.length ? (
            <p className="small muted">Caveats: {campaign.campaign_summary.caveats.join(" ")}</p>
          ) : null}
        </>
      ) : null}

      {sweeps.length > 0 && (
        <>
          <h3>Ranking sensitivity (how much the review list depends on the Researcher's weights)</h3>
          <div className="table-wrap">
            <table className="data small">
              <thead>
                <tr>
                  <th>Dataset</th><th>Round</th><th>Weightings × resamples</th><th>Kept on list (weights only)</th>
                  <th>Worst weighting</th><th>Weights + bootstrap</th><th>Consensus vs default</th>
                </tr>
              </thead>
              <tbody>
                {sweeps.map(([name, s]) => (
                  <tr key={name}>
                    <td className="strong">{name}</td>
                    <td>{s.round}</td>
                    <td>{s.weight_combinations} × {s.resamples_completed.toLocaleString()}</td>
                    <td>{pct(s.weights_only.mean_overlap_with_default)}</td>
                    <td>{pct(s.weights_only.min_overlap_with_default)}</td>
                    <td>{pct(s.weights_and_bootstrap.mean_overlap_with_default)}</td>
                    <td>{pct(s.consensus_overlap_with_default)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </section>
  );
}
