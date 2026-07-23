import { CopyField } from "./copy-field";
import {
  formatConfidence,
  formatCost,
  statusLabel,
} from "./format-investigation-values";
import type {
  InvestigationView,
  InvestigationWorkspaceData,
} from "./investigation-types";
import styles from "./cited-conclusion.module.css";

interface CitedConclusionProps {
  investigation: InvestigationView;
  correlationId: string;
  projectionSafety: InvestigationWorkspaceData["projectionSafety"];
}

export function CitedConclusion({
  investigation,
  correlationId,
  projectionSafety,
}: CitedConclusionProps) {
  const analysis = investigation.analysis;
  const hypothesis = analysis?.hypotheses[0];
  const alternatives = analysis?.hypotheses.slice(1) ?? [];
  const evidenceLabels = new Map(
    investigation.evidenceIds.map((evidenceId, index) => [
      evidenceId,
      `E-${String(index + 1).padStart(2, "0")}`,
    ]),
  );
  const completed = investigation.status === "COMPLETED" &&
    analysis?.status === "complete" &&
    hypothesis !== undefined;

  return (
    <aside className={styles.conclusion} aria-labelledby="conclusion-title">
      <header>
        <div>
          <p>Evidence-grounded output</p>
          <h2 id="conclusion-title">Cited conclusion</h2>
        </div>
        <span data-complete={completed}>{completed ? "Complete" : statusLabel(investigation.status)}</span>
      </header>

      {completed ? (
        <>
          <article className={styles.finding}>
            <div className={styles.score}>
              <span>Confidence</span>
              <strong>{formatConfidence(hypothesis.confidence)}</strong>
            </div>
            <h3>{hypothesis.title}</h3>
            <p>{hypothesis.explanation}</p>
            <EvidenceReferences hypothesis={hypothesis} evidenceLabels={evidenceLabels} />
          </article>

          <section className={styles.citations} aria-labelledby="citations-title">
            <h3 id="citations-title">Persisted citations</h3>
            <ol>
              {analysis.citations.map((citation, index) => (
                <li key={`${citation.evidenceId}:${citation.digest}:${index}`}>
                  <span>{evidenceLabel(evidenceLabels, citation.evidenceId)}</span>
                  <p>{citation.claim}</p>
                  <code>{citation.digest}</code>
                </li>
              ))}
            </ol>
          </section>

          {alternatives.length > 0 ? (
            <section className={styles.alternatives} aria-labelledby="alternatives-title">
              <h3 id="alternatives-title">Other bounded hypotheses</h3>
              <ol>
                {alternatives.map((alternative, index) => (
                  <li key={`${alternative.title}:${index}`}>
                    <div>
                      <h4>{alternative.title}</h4>
                      <strong>{formatConfidence(alternative.confidence)}</strong>
                    </div>
                    <p>{alternative.explanation}</p>
                    <EvidenceReferences
                      hypothesis={alternative}
                      evidenceLabels={evidenceLabels}
                    />
                  </li>
                ))}
              </ol>
            </section>
          ) : null}

          {analysis.counterEvidence.length > 0 ? (
            <section className={styles.counterEvidence} aria-labelledby="counter-evidence-title">
              <h3 id="counter-evidence-title">Counter-evidence recorded</h3>
              <ul>
                {analysis.counterEvidence.map((item, index) => (
                  <li key={`${item}:${index}`}>{item}</li>
                ))}
              </ul>
            </section>
          ) : null}

          {analysis.missingEvidence.length > 0 ? (
            <section className={styles.counterEvidence} aria-labelledby="evidence-gaps-title">
              <h3 id="evidence-gaps-title">Known evidence gaps</h3>
              <ul>
                {analysis.missingEvidence.map((item, index) => (
                  <li key={`${item}:${index}`}>{item}</li>
                ))}
              </ul>
            </section>
          ) : null}
        </>
      ) : (
        <section className={styles.stopped} aria-live="polite">
          <h3>No authoritative conclusion</h3>
          <p>
            {investigation.terminalReason ??
              "A completed, cited analysis has not been accepted by the investigation reducer."}
          </p>
          <p>No remediation action was exposed.</p>
        </section>
      )}

      {analysis ? (
        <section className={styles.provenance} aria-labelledby="provenance-title">
          <h3 id="provenance-title">Projection checks</h3>
          <dl>
            <div><dt>Display policy</dt><dd>{projectionSafety.classification}</dd></div>
            <div><dt>Redaction policy</dt><dd>{projectionSafety.redactionVersion}</dd></div>
            <div><dt>Redactions</dt><dd>{projectionSafety.redactionCount}</dd></div>
            <div><dt>Contract</dt><dd>{analysis.schemaVersion}</dd></div>
            <div><dt>Model ID</dt><dd>{analysis.modelId}</dd></div>
            <div><dt>Prompt contract</dt><dd>{analysis.promptVersion}</dd></div>
            <div><dt>Usage</dt><dd>{analysis.usage.totalTokens} tokens</dd></div>
            <div><dt>Estimated cost</dt><dd>{formatCost(analysis.costEstimate.amount)}</dd></div>
          </dl>
        </section>
      ) : null}

      <section className={styles.correlation} aria-labelledby="correlation-title">
        <h3 id="correlation-title">Request correlation</h3>
        <CopyField label="correlation ID" value={correlationId} />
        <p>
          This ID covers the operator read request. Cross-service trace proof is
          not present in the current public projection.
        </p>
      </section>
    </aside>
  );
}

interface EvidenceReferencesProps {
  hypothesis: NonNullable<InvestigationView["analysis"]>["hypotheses"][number];
  evidenceLabels: ReadonlyMap<string, string>;
}

function EvidenceReferences({ hypothesis, evidenceLabels }: EvidenceReferencesProps) {
  return (
    <p className={styles.references}>
      Evidence refs:{" "}
      {hypothesis.citations.map((citation, index) => (
        <span key={`${citationKey(citation)}:${index}`}>
          {evidenceLabel(evidenceLabels, citation.evidenceId)}
          {index === hypothesis.citations.length - 1 ? "" : ", "}
        </span>
      ))}
    </p>
  );
}

function citationKey(
  citation: NonNullable<InvestigationView["analysis"]>["citations"][number],
): string {
  return `${citation.evidenceId}\u0000${citation.digest}\u0000${citation.claim}`;
}

function evidenceLabel(labels: ReadonlyMap<string, string>, evidenceId: string): string {
  const label = labels.get(evidenceId);
  if (label === undefined) throw new Error("Citation evidence label is unavailable.");
  return label;
}
