// Shapes of the JSON records served by the Java fog server (see backend ApiServer / ResearchTeam).

export type StageStatus = "pending" | "running" | "complete" | "complete_with_warnings" | "failed";

export interface ToolCall {
  tool: string;
  ok: boolean;
  ms: number;
  error?: string;
}

export interface Stage {
  role: string;
  phase: string;
  title: string;
  status: StageStatus;
  mode?: string;
  model?: string | null;
  started_at?: string;
  finished_at?: string;
  seconds?: number;
  summary?: string;
  warnings?: string[];
  tool_calls?: ToolCall[];
  tokens?: { input: number; output: number };
}

export interface Run {
  run_id: string;
  status: "running" | "complete" | "complete_with_issues" | "failed" | "interrupted";
  started_at: string;
  finished_at: string | null;
  agent_mode: string;
  model: string | null;
  host: { cores: number; os: string; java: string };
  stages: Stage[];
  outcome: {
    dataset_sha256?: string;
    ambiguous_events?: number;
    tests?: { verdict: string; passed: number; total: number };
    qa?: { status: string; warnings: number; failures: number };
  };
}

export interface Summary {
  headline: string;
  highlights: string[];
  next_steps: string[];
  stats: {
    events_checked: number;
    ambiguous_events: number;
    ambiguous_definition: string;
    most_common_class: string;
    top_uncertain_class: string;
    qa_status: string;
    tests: string;
  };
  written_by: string;
}

export interface AppState {
  agent_mode: string;
  model: string | null;
  running: boolean;
  run: Run | null;
  results_run_id: string | null;
  summary?: Summary;
}

export interface ClassProb {
  class: string;
  probability: number;
}

export interface RankedEvent {
  rank: number;
  gravityspy_id: string;
  event_time: string;
  ifo: string;
  ml_label: string;
  ml_confidence: number;
  argmax_label: string;
  label_conflict: boolean;
  top_classes: ClassProb[];
  scores: {
    priority: number;
    entropy_nats: number;
    entropy_norm: number;
    margin: number;
    outlier_rms_z: number;
    outlier_score: number;
    contributions: { entropy: number; margin: number; outlier: number; label_conflict: number };
    percentiles: { entropy_higher_than: number; margin_closer_than: number; outlier_higher_than: number };
  };
  baseline: string;
  outlier_features: { feature: string; value: number; baseline_median: number; robust_z: number }[];
  metadata: Record<string, number>;
  spectrograms: { window: string; url: string }[];
  stability: number | null;
  drivers: string[];
  explanation: string;
}

export interface Ranked {
  run_id: string;
  events_scored: number;
  top_n: number;
  spec: { formula: string; weights: Record<string, number>; author: string; limitations: string[]; rationale: string };
  events: RankedEvent[];
}

export interface ClassUncertainty {
  class: string;
  events: number;
  ambiguous: number;
  ambiguous_share: number;
  mean_entropy_norm: number;
  mean_margin: number;
}

export interface Findings {
  events_checked: number;
  ambiguous_definition: string;
  ambiguous_events: number;
  ambiguous_share: number;
  most_common_class: string;
  class_uncertainty: ClassUncertainty[];
  confusion_pairs: { pair: string; ambiguous_events: number }[];
  label_conflicts: { count: number; examples: string[] };
  rank_stability: { resamples: number; seconds: number; threads: number; mean_top_n_stability: number } | null;
  next_steps: string[];
  limitations: string[];
}

export interface QaCheck {
  check: string;
  status: "pass" | "warn" | "fail";
  detail: string;
  affected: number;
  examples: string[];
}

export interface Qa {
  status: "passed" | "passed_with_warnings" | "failed";
  summary: string;
  checks: QaCheck[];
}

export interface Tests {
  verdict: "pass" | "fail";
  passed: number;
  total: number;
  summary: string;
  tests: { name: string; area: string; passed: boolean; detail: string; ms: number }[];
}

export interface BusEvent {
  seq: number;
  ts: string;
  type: "agent" | "state" | "log";
  role: string | null;
  text: string;
  data?: { kind?: string; tool?: string; ok?: boolean; mode?: string };
}

export interface Answer {
  question: string;
  answer: string;
  sources: string[];
  mode: string;
  asked_at: string;
}

export interface CampaignDataset {
  dataset: string;
  detector: string;
  observing_run: string;
  status: string;
  run_id?: string;
  events?: number;
  ambiguous?: number;
  ambiguous_share?: number;
  label_conflicts?: number;
  placeholder_url_events?: number;
  top_confusion_pair?: string | null;
  top_confusion_pair_events?: number;
  top_uncertain_class_by_share?: string;
  mean_top_n_stability?: number;
  qa_status?: string;
  tests?: string;
  seconds?: number;
}

export interface SweepResult {
  round: number;
  grid_step: number;
  weight_combinations: number;
  resamples_completed: number;
  top_n_rankings_evaluated: number;
  seconds: number;
  weights_only: { mean_overlap_with_default: number; min_overlap_with_default: number; share_of_weightings_with_overlap_ge_0_8: number };
  weights_and_bootstrap: { mean_overlap_with_default: number };
  consensus_overlap_with_default: number | null;
  consensus_top_5: { gravityspy_id: string; ml_label: string; frequency: number; default_rank: number }[];
}

export interface Campaign {
  campaign_id: string;
  status: "running" | "complete" | "failed";
  phase: "datasets" | "comparison" | "sensitivity" | "complete";
  started_at: string;
  deadline: string;
  finished_at?: string;
  hours: number;
  agent_mode: string;
  datasets: CampaignDataset[];
  comparison?: { total_events: number; total_ambiguous: number; datasets_compared: number; highlights: string[] };
  campaign_summary?: { headline: string; highlights: string[]; caveats: string[]; written_by: string };
  sweep_round?: number;
  sensitivity: Record<string, SweepResult>;
}
