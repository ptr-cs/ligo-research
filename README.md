# Gravity Spy Glitch Triage

Proof of concept: a five-role Claude agent team ranks ambiguous LIGO glitches from the public Gravity Spy
H1/O1 dataset for human review. A Java fog server (the Mac mini) does the heavy computation; a TypeScript
dashboard shows the team's progress live.

**Research question:** which Gravity Spy events most deserve human review because the classifier is uncertain,
two classes compete closely, or the event's metadata is unusual for its predicted class?

## Run it

```bash
export ANTHROPIC_API_KEY=...   # optional; without it every role runs a scripted policy
./run.sh                       # build web + backend, run the team, serve http://localhost:8787
```

| Command | What it does |
|---|---|
| `./run.sh` | Build everything, run the team once, serve the dashboard on :8787 |
| `./run.sh analyze` | Run the team once and exit (exit code 1 if tests or QA fail) |
| `./run.sh test` | JUnit suite |
| `./run.sh serve` | Serve existing results without a new run |

| `./run.sh campaign [HOURS]` | Time-boxed fog-server campaign over all 8 datasets (default 4 h), see below |

Needs Java 21 and Node 18+. Maven is bootstrapped into `.tools/` automatically if it isn't installed.
For dashboard development, run `npm run dev` in `web/` (it proxies `/api` to :8787).
`TRIAGE_MODEL` overrides the agent model (default `claude-opus-5-5`).

## The team

Each role is a Claude agent driving its own Java tools through a tool-use loop
([AgentRunner](backend/src/main/java/org/ligo/triage/agents/AgentRunner.java)). A role can only call the tools it
was granted, so separation of duties is enforced in code. Without an API key, or if a Claude call fails, the role
falls back to a scripted policy that calls the same tools; `runs.json` records which mode ran.

| Order | Role | Tools | Deliverable |
|---|---|---|---|
| 1 | Developer (ingest) | `download_dataset`, `validate_schema` | cached CSV, checksum, schema report |
| 2 | Researcher | `inspect_dataset_profile`, `submit_ranking_spec` | `spec.json`: weights, thresholds, limitations |
| 3 | Developer (analysis) | `compute_scores`, `run_rank_stability`, `freeze_outputs` | `scores.csv`, `ranked.json`, `findings.json`, read-only + `manifest.json` |
| 4a | Tester | `run_test_suite`, `submit_test_report` | `tests.json` |
| 4b | QA (parallel with 4a) | six read-only checks, `submit_qa_report` | `qa.json` |
| 5 | Reporter | `read_team_file`, `get_ranked_event`, `submit_summary` | `summary.json`; also answers "Ask the team" questions |

After each stage the orchestrator ([ResearchTeam](backend/src/main/java/org/ligo/triage/agents/ResearchTeam.java))
checks that the deliverable exists. If an agent skipped a mechanical step, the orchestrator completes it and
records a warning. QA and test verdicts come from the check results, so an agent can't report a pass that the
checks didn't produce.

## Ranking

```
priority = w_entropy·H/ln(22) + w_margin·(1 − (p1 − p2)) + w_outlier·min(rms_z, cap)/cap + bonus·[ml_label ≠ argmax]
```

- **Entropy** is computed over the 22 class probabilities. **Margin** is the gap between the top two classes.
- **Outlier** is the RMS of robust z-scores (median/MAD) of log10 metadata, measured against the predicted
  class's baseline. Classes below `min_class_size` use the global baseline instead.
- **Bootstrap rank stability** resamples the dataset, rebuilds the baselines and re-ranks every event. 1,000
  resamples (15M row rescorings) take about 4 s on the 12-core Mac mini.
- Every ranked event has a plain-language explanation, percentiles, a breakdown of each term's contribution,
  the competing classes and its four spectrograms.

## Results from the scripted-mode run (default spec)

- 15,305 events checked; 806 are ambiguous (margin < 0.2).
- Most frequent competing pair: Low_Frequency_Burst ↔ Low_Frequency_Lines (254 ambiguous events). Whistle has
  the highest ambiguous share (27.6%).
- **QA: passed with warnings.** These are real properties of the published dataset:
  - 105 events have an `ml_label` that differs from the argmax of their own probabilities.
  - 39 events have placeholder `?` spectrogram URLs.
- **Tests:** 15/15 runtime checks pass, plus 18 JUnit tests. The determinism check re-scores the raw data and
  matches the frozen `scores.csv` byte-for-byte.

## Campaign mode (multi-hour fog-server workload)

`./run.sh campaign 4` serves the dashboard and works through a timed plan, saving progress to
`output/campaign/campaign.json` after every step:

1. **Datasets.** The full team pipeline runs on every Gravity Spy file (H1/L1 × O1, O2, O3a, O3b; about 742 MB).
2. **Comparison.** Hanford vs Livingston, and the trend across observing runs. The Reporter summarises it.
3. **Sensitivity sweep, until the deadline.** For every weight combination on a simplex grid (66, then 231
   combinations), with and without bootstrap-resampled metadata baselines (50 resamples at first, up to 2,000),
   the Researcher re-derives each dataset's top-25 list. It then measures how much of the default list survives
   and builds a consensus list that is robust to the formula. Per-round history is in
   `output/campaign/sensitivity/<dataset>.json`.

Unclassified events (all 22 probabilities 0; for example 52 rows in L1_O1) are quarantined. They're counted and
reported by schema validation and QA, and excluded from scoring.

To keep a campaign alive after the terminal closes and stop the Mac mini idle-sleeping:

```bash
nohup caffeinate -i java -Xmx12g -jar backend/target/gravity-spy-triage.jar campaign --hours 4 --root "$PWD" \
  > output/campaign/campaign.log 2>&1 &
```

## Layout

```
run.sh                      one-command reproduction
backend/                    Java 21 fog server (Maven): data/, analysis/, qa/, testing/, agents/, server/
web/                        React + TypeScript dashboard (Vite)
data/cache/H1_O1.csv        cached dataset (Zenodo 5649212, sha256 3072bb98…)
output/runs.json            every run and agent stage (live)
output/runs/<run_id>/       per-run records; developer files are read-only, checksummed in manifest.json
```

## API (port 8787)

`GET /api/state` · `GET /api/events` (SSE) · `GET /api/{ranked,findings,qa,tests,dataset,spec,summary,runs}` ·
`POST /api/run` · `POST /api/ask {"question": "..."}`

This doesn't claim a new glitch class. It shows that an agent team can ingest real scientific data, run a
bounded analysis, validate it independently and report progress with evidence. Natural next steps: compare H1
with L1, and test whether uncertainty patterns changed across O1 → O2 → O3.
