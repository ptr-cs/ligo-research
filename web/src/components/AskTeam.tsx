import { useState, type FormEvent } from "react";
import { api } from "../api";
import type { Answer } from "../types";

const SUGGESTIONS = [
  "What has the team completed?",
  "Did QA find any problems?",
  "Why is this glitch ranked first?",
  "Which classes generate the most uncertainty?",
  "What should the team investigate next?",
];

export function AskTeam({ disabled }: { disabled: boolean }) {
  const [question, setQuestion] = useState("");
  const [answers, setAnswers] = useState<Answer[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const ask = async (q: string) => {
    if (!q.trim() || busy) return;
    setBusy(true);
    setError(null);
    try {
      const a = await api.ask(q.trim());
      setAnswers((prev) => [a, ...prev].slice(0, 20));
      setQuestion("");
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const submit = (e: FormEvent) => {
    e.preventDefault();
    ask(question);
  };

  return (
    <section className="panel ask">
      <h2>Ask the research team</h2>
      <form className="ask-form" onSubmit={submit}>
        <input
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          placeholder={disabled ? "Available once the first run has produced results" : "e.g. Why is rank 3 on the list?"}
          disabled={disabled || busy}
          maxLength={500}
          aria-label="Question for the research team"
        />
        <button className="btn" type="submit" disabled={disabled || busy || !question.trim()}>{busy ? "Asking…" : "Ask"}</button>
      </form>
      <div className="suggestions">
        {SUGGESTIONS.map((s) => (
          <button key={s} className="chip-btn small" disabled={disabled || busy} onClick={() => ask(s)}>{s}</button>
        ))}
      </div>
      {error && <div className="banner banner-error small">{error}</div>}
      <ul className="answers">
        {answers.map((a) => (
          <li key={a.asked_at} className="answer">
            <div className="q">{a.question}</div>
            <div className="a">{a.answer}</div>
            <div className="small muted">
              Sources: {a.sources.length ? a.sources.join(", ") : "none"} · {a.mode === "claude" ? "Claude Reporter" : `Reporter (${a.mode})`}
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}
