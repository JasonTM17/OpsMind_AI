import { CopyField } from "./copy-field";
import { formatConfidence, formatUtc } from "./format-investigation-values";
import type { InvestigationView } from "./investigation-types";
import styles from "./investigation-context.module.css";

interface InvestigationContextProps {
  investigation: InvestigationView;
}

export function InvestigationContext({ investigation }: InvestigationContextProps) {
  const hypothesis = investigation.analysis?.hypotheses[0];
  const confidence = hypothesis?.confidence ?? investigation.analysis?.confidence ?? null;
  const remaining = {
    rounds: Math.max(0, investigation.budget.maxRounds - investigation.rounds),
    tools: Math.max(0, investigation.budget.maxToolCalls - investigation.toolCalls),
    evidence: Math.max(0, investigation.budget.maxEvidenceItems - investigation.evidenceIds.length),
    tokens: Math.max(0, investigation.budget.maxTokens - investigation.totalTokens),
  };

  return (
    <aside className={styles.context} aria-labelledby="investigation-context-title">
      <div className={styles.sectionHeading}>
        <p>Current assessment</p>
        <h2 id="investigation-context-title">Hypothesis</h2>
      </div>
      {hypothesis ? (
        <div className={styles.hypothesis}>
          <h3>{hypothesis.title}</h3>
          <p>{hypothesis.explanation}</p>
          <div className={styles.confidence}>
            <div>
              <span>Confidence</span>
              <strong>{formatConfidence(confidence)}</strong>
            </div>
            {confidence !== null ? (
              <meter min="0" max="1" value={confidence}>
                {Math.round(confidence * 100)}%
              </meter>
            ) : null}
          </div>
        </div>
      ) : (
        <p className={styles.empty}>No bounded hypothesis has been accepted.</p>
      )}

      <section className={styles.budget} aria-labelledby="budget-title">
        <h3 id="budget-title">Session constraints</h3>
        <dl>
          <div><dt>Rounds remaining</dt><dd>{remaining.rounds}</dd></div>
          <div><dt>Read calls remaining</dt><dd>{remaining.tools}</dd></div>
          <div><dt>Evidence slots</dt><dd>{remaining.evidence}</dd></div>
          <div><dt>Tokens remaining</dt><dd>{remaining.tokens.toLocaleString("en-US")}</dd></div>
        </dl>
        <p className={styles.deadline}>
          Deadline <time dateTime={investigation.deadlineAt}>{formatUtc(investigation.deadlineAt)}</time>
        </p>
      </section>

      <section className={styles.boundary} aria-labelledby="safety-title">
        <h3 id="safety-title">Read-only safety boundary</h3>
        <ul>
          <li>Catalog selectors only</li>
          <li>No browser-to-tool connection</li>
          <li>No remediation authority</li>
          <li>Durable citations required</li>
        </ul>
      </section>

      <section className={styles.identifier} aria-labelledby="run-id-title">
        <h3 id="run-id-title">Run identifier</h3>
        <CopyField label="run ID" value={investigation.runId} />
      </section>
    </aside>
  );
}
