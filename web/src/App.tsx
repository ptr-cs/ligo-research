import { useCallback, useEffect, useRef, useState } from "react";
import { api, type LinkStatus } from "./api";
import type { AppState, BusEvent, Campaign, Findings, Qa, Ranked, Summary, Tests } from "./types";
import { Header } from "./components/Header";
import { AgentActivity } from "./components/AgentActivity";
import { ResearchSummary } from "./components/ResearchSummary";
import { GlitchList } from "./components/GlitchList";
import { AskTeam } from "./components/AskTeam";
import { CampaignPanel } from "./components/CampaignPanel";

const MAX_EVENTS = 200;

export default function App() {
  const [state, setState] = useState<AppState | null>(null);
  const [ranked, setRanked] = useState<Ranked | null>(null);
  const [findings, setFindings] = useState<Findings | null>(null);
  const [qa, setQa] = useState<Qa | null>(null);
  const [tests, setTests] = useState<Tests | null>(null);
  const [summary, setSummary] = useState<Summary | null>(null);
  const [events, setEvents] = useState<BusEvent[]>([]);
  const [link, setLink] = useState<LinkStatus>("offline");
  const lastSeq = useRef(0);
  const lastTs = useRef("");
  const [error, setError] = useState<string | null>(null);
  const [campaign, setCampaign] = useState<Campaign | null>(null);
  const [selectedRun, setSelectedRun] = useState<string | null>(null); // null = latest run
  const resultsRun = useRef<string | null>(null);
  const refreshTimer = useRef<number | undefined>(undefined);

  const selectedRef = useRef<string | null>(null);
  selectedRef.current = selectedRun;

  const refreshResults = useCallback(async () => {
    const run = selectedRef.current;
    const [r, f, q, t, sm] = await Promise.all([api.ranked(run), api.findings(run), api.qa(run), api.tests(run), api.summary(run)]);
    setSummary(sm);
    setRanked(r);
    setFindings(f);
    setQa(q);
    setTests(t);
  }, []);

  const refreshState = useCallback(async () => {
    try {
      const [s, c] = await Promise.all([api.state(), api.campaign()]);
      setState(s);
      setCampaign(c);
      setError(null);
      // Reload the result records whenever a new run publishes output or QA/tests land.
      const outcomeKey = s ? `${selectedRef.current}|${s.results_run_id}|${s.run?.outcome.qa?.status}|${s.run?.outcome.tests?.verdict}|${s.summary?.headline}` : null;
      if (outcomeKey !== resultsRun.current) {
        resultsRun.current = outcomeKey;
        await refreshResults();
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [refreshResults]);

  useEffect(() => {
    refreshState();
    const unsubscribe = api.events(
      (e) => {
        // Polling re-delivers recent events; a newer timestamp with a lower seq means the server restarted.
        if (e.seq <= lastSeq.current && e.ts <= lastTs.current) return;
        lastSeq.current = e.seq;
        lastTs.current = e.ts;
        setEvents((prev) => [...prev, e].slice(-MAX_EVENTS));
        if (e.type === "state") {
          window.clearTimeout(refreshTimer.current);
          refreshTimer.current = window.setTimeout(refreshState, 150);
        }
      },
      (status) => {
        setLink((prev) => {
          if (prev === "offline" && status !== "offline") refreshState();
          return status;
        });
      },
    );
    const poll = window.setInterval(refreshState, 15000); // safety net if the stream drops
    return () => {
      unsubscribe();
      window.clearInterval(poll);
    };
  }, [refreshState]);

  const selectRun = (runId: string | null) => {
    setSelectedRun(runId);
    selectedRef.current = runId;
    resultsRun.current = null;
    refreshState();
  };

  const viewedRun = selectedRun ?? state?.results_run_id ?? null;

  const startRun = async () => {
    if (!(await api.startRun())) setError("A run is already in progress");
  };

  return (
    <div className="app">
      <Header state={state} qa={qa} link={link} onRun={startRun} />
      {error && <div className="banner banner-error">Not connected to the fog server: {error}</div>}
      {campaign && <CampaignPanel campaign={campaign} selectedRun={selectedRun} onSelectRun={selectRun} />}
      {viewedRun && (
        <div className="viewing small">
          Showing results for <code>{viewedRun}</code>
          {selectedRun ? <button className="link" onClick={() => selectRun(null)}>Follow latest run</button> : <span className="muted"> (latest)</span>}
        </div>
      )}
      <main className="grid">
        <AgentActivity run={state?.run ?? null} events={events} mode={state?.agent_mode} model={state?.model ?? null} />
        <ResearchSummary summary={summary ?? undefined} findings={findings} tests={tests} qa={qa} ranked={ranked} />
        <GlitchList ranked={ranked} />
        <AskTeam disabled={!state?.results_run_id} />
      </main>
      <footer className="footer">
        Data: Gravity Spy machine-learning classifications, LIGO Hanford and Livingston, O1–O3b (Zenodo 5649212). Triage aid only; rankings are not ground truth.
      </footer>
    </div>
  );
}
