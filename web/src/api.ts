import type { Answer, AppState, BusEvent, Campaign, Findings, Qa, Ranked, Summary, Tests } from "./types";

/**
 * Where the Java fog server lives. Same origin by default (the fog server also serves this app). A hosted copy
 * points elsewhere via, in priority order: ?api=https://… in the URL (remembered in this browser), or the
 * VITE_API_BASE build variable. Use ?api=same to clear a remembered address.
 */
function resolveApiBase(): string {
  const KEY = "triage.apiBase";
  const fromUrl = new URLSearchParams(window.location.search).get("api");
  try {
    if (fromUrl === "same") localStorage.removeItem(KEY);
    else if (fromUrl) localStorage.setItem(KEY, fromUrl);
  } catch {
    // storage unavailable (private mode); the URL parameter still applies for this page load
  }
  let stored: string | null = null;
  try { stored = localStorage.getItem(KEY); } catch { /* ignore */ }
  const base = (fromUrl && fromUrl !== "same" ? fromUrl : stored) ?? import.meta.env.VITE_API_BASE ?? "";
  return base.replace(/\/+$/, "");
}

export const API_BASE = resolveApiBase();
const url = (path: string) => `${API_BASE}/api/${path}`;
const where = () => API_BASE || window.location.origin;

export type LinkStatus = "live" | "polling" | "offline";

const withRun = (path: string, run?: string | null) => (run ? `${path}?run=${encodeURIComponent(run)}` : path);

/** Parses a JSON response, explaining the usual misconfiguration instead of surfacing a bare parse error. */
async function json<T>(res: Response, path: string): Promise<T> {
  const type = res.headers.get("content-type") ?? "";
  if (!type.includes("application/json")) {
    throw new Error(`${where()}/api/${path} returned ${type.split(";")[0] || "non-JSON"} (HTTP ${res.status}), not fog-server JSON. `
      + "This page isn't connected to the Java server: open it with ?api=https://<your-tunnel-url> or build with VITE_API_BASE.");
  }
  return (await res.json()) as T;
}

async function get<T>(path: string): Promise<T | null> {
  let res: Response;
  try {
    res = await fetch(url(path));
  } catch {
    throw new Error(`cannot reach ${where()} (is the fog server running and the tunnel up?)`);
  }
  if (res.status === 404 && (res.headers.get("content-type") ?? "").includes("application/json")) return null;
  if (!res.ok && res.status !== 404) throw new Error(`${path}: HTTP ${res.status}`);
  return json<T>(res, path);
}

export const api = {
  state: () => get<AppState>("state"),
  campaign: () => get<Campaign>("campaign"),
  ranked: (run?: string | null) => get<Ranked>(withRun("ranked", run)),
  findings: (run?: string | null) => get<Findings>(withRun("findings", run)),
  qa: (run?: string | null) => get<Qa>(withRun("qa", run)),
  tests: (run?: string | null) => get<Tests>(withRun("tests", run)),
  summary: (run?: string | null) => get<Summary>(withRun("summary", run)),

  async startRun(): Promise<boolean> {
    const res = await fetch(url("run"), { method: "POST" });
    return res.status === 202;
  },

  async ask(question: string): Promise<Answer> {
    const res = await fetch(url("ask"), {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ question }),
    });
    const body = await json<{ error?: string } & Answer>(res, "ask");
    if (!res.ok) throw new Error(body.error ?? `HTTP ${res.status}`);
    return body as Answer;
  },

  /**
   * Subscribes to the server's live events; returns an unsubscribe function. Some proxies (e.g. Cloudflare quick
   * tunnels) hold back event streams, so if no event arrives shortly after connecting this falls back to polling
   * the recent-activity endpoint.
   */
  events(onEvent: (e: BusEvent) => void, onStatus: (status: LinkStatus) => void): () => void {
    let gotEvent = false;
    let pollTimer: number | undefined;
    const source = new EventSource(url("events"));
    const poll = async () => {
      try {
        const recent = await get<BusEvent[]>("activity");
        recent?.forEach(onEvent);
        onStatus("polling");
      } catch {
        onStatus("offline");
      }
    };
    source.onopen = () => { if (pollTimer === undefined) onStatus("live"); };
    source.onerror = () => { if (pollTimer === undefined) onStatus("offline"); };
    source.onmessage = (msg) => {
      gotEvent = true;
      onEvent(JSON.parse(msg.data) as BusEvent);
    };
    // The server replays recent events immediately on connect, so silence means the stream is being buffered.
    const fallback = window.setTimeout(() => {
      if (gotEvent) return;
      source.close();
      poll();
      pollTimer = window.setInterval(poll, 3000);
    }, 5000);
    return () => {
      window.clearTimeout(fallback);
      window.clearInterval(pollTimer);
      source.close();
    };
  },
};
