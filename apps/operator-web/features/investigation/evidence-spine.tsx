import { CopyField } from "./copy-field";
import { formatUtc, statusLabel } from "./format-investigation-values";
import type { InvestigationView } from "./investigation-types";
import styles from "./evidence-spine.module.css";

interface EvidenceSpineProps {
  investigation: InvestigationView;
}

export function EvidenceSpine({ investigation }: EvidenceSpineProps) {
  const analysis = investigation.analysis;
  const citationsByEvidence = new Map<
    string,
    NonNullable<InvestigationView["analysis"]>["citations"]
  >();
  for (const citation of analysis?.citations ?? []) {
    const citations = citationsByEvidence.get(citation.evidenceId) ?? [];
    citationsByEvidence.set(citation.evidenceId, [...citations, citation]);
  }

  return (
    <section className={styles.spine} aria-labelledby="evidence-spine-title">
      <header className={styles.heading}>
        <div>
          <p>Authorized projection</p>
          <h2 id="evidence-spine-title">Evidence spine</h2>
        </div>
        <span>{investigation.evidenceIds.length} durable reference(s)</span>
      </header>

      <ol className={styles.timeline}>
        <SpineStep
          state="stable"
          label="Run accepted"
          time={investigation.startedAt}
          source="Platform API"
        >
          <p>
            Investigation admitted with {investigation.budget.maxRounds} rounds,
            {" "}{investigation.budget.maxToolCalls} catalog reads, and
            {" "}{investigation.budget.maxTokens.toLocaleString("en-US")} tokens.
          </p>
        </SpineStep>

        <SpineStep
          state={analysis === null ? "pending" : "stable"}
          label="Analysis boundary"
          source="AI Runtime projection"
        >
          {analysis ? (
            <>
              <p>
                {statusLabel(analysis.status)} after {investigation.rounds} accepted
                {" "}round{investigation.rounds === 1 ? "" : "s"}.
              </p>
              <dl className={styles.metadata}>
                <div><dt>Model</dt><dd>{analysis.modelId}</dd></div>
                <div><dt>Schema</dt><dd>{analysis.schemaVersion}</dd></div>
                <div><dt>Prompt contract</dt><dd>{analysis.promptVersion}</dd></div>
                <div><dt>Tokens</dt><dd>{analysis.usage.totalTokens}</dd></div>
              </dl>
            </>
          ) : (
            <p>No validated analysis projection is available yet.</p>
          )}
        </SpineStep>

        <SpineStep
          state={investigation.pendingToolCalls.length > 0
            ? "pending"
            : investigation.toolCalls > 0 ? "stable" : "pending"}
          label="Catalog intent boundary"
          source="Platform investigation projection"
        >
          <p>
            {investigation.toolCalls} accepted read intent
            {investigation.toolCalls === 1 ? "" : "s"} counted against budget.
            Completion history and executable query text are intentionally absent
            from the browser contract.
          </p>
          {investigation.pendingToolCalls.length > 0 ? (
            <ul className={styles.intentList}>
              {investigation.pendingToolCalls.map((intent) => (
                <li key={intent.intentId}>
                  <strong>{intent.connector}.{intent.operation}</strong>
                  <span>Reviewed catalog label; executable arguments remain server-side.</span>
                  <code>{intent.argumentsDigest}</code>
                </li>
              ))}
            </ul>
          ) : null}
        </SpineStep>

        <SpineStep
          state={investigation.evidenceIds.length > 0 ? "stable" : "pending"}
          label="Durable evidence"
          source="Platform evidence store"
        >
          {investigation.evidenceIds.length === 0 ? (
            <p>No evidence reference has been accepted.</p>
          ) : (
            <ul className={styles.evidenceList}>
              {investigation.evidenceIds.map((evidenceId, index) => {
                const citations = citationsByEvidence.get(evidenceId) ?? [];
                return (
                  <li key={evidenceId}>
                    <div className={styles.evidenceLabel}>
                      <span>E-{String(index + 1).padStart(2, "0")}</span>
                      <strong>{citations.length > 0 ? "Cited evidence" : "Durable evidence"}</strong>
                    </div>
                    {citations.length > 0 ? (
                      <ul className={styles.citationClaims}>
                        {citations.map((citation, citationIndex) => (
                          <li key={`${citation.digest}:${citation.claim}:${citationIndex}`}>
                            <p>{citation.claim}</p>
                            <code className={styles.digest}>{citation.digest}</code>
                          </li>
                        ))}
                      </ul>
                    ) : null}
                    <CopyField label={`evidence ${index + 1} ID`} value={evidenceId} />
                  </li>
                );
              })}
            </ul>
          )}
        </SpineStep>

        <SpineStep
          state={terminalState(investigation.status)}
          label={statusLabel(investigation.status)}
          time={investigation.endedAt ?? undefined}
          source="Investigation reducer"
        >
          <p>
            {investigation.terminalReason ??
              terminalDescription(investigation.status, analysis?.citations.length ?? 0)}
          </p>
        </SpineStep>
      </ol>
    </section>
  );
}

interface SpineStepProps {
  state: "stable" | "pending" | "stopped";
  label: string;
  source: string;
  time?: string;
  children: React.ReactNode;
}

function SpineStep({ state, label, source, time, children }: SpineStepProps) {
  return (
    <li className={styles.step} data-state={state}>
      <span className={styles.marker} aria-hidden="true" />
      <article>
        <header>
          <div>
            <h3>{label}</h3>
            <span>{source}</span>
          </div>
          {time ? <time dateTime={time}>{formatUtc(time)}</time> : <span>Time unavailable</span>}
        </header>
        <div className={styles.stepBody}>{children}</div>
      </article>
    </li>
  );
}

function terminalState(status: InvestigationView["status"]): SpineStepProps["state"] {
  if (status === "COMPLETED") return "stable";
  if (["CREATED", "ANALYZING", "WAITING_FOR_EVIDENCE"].includes(status)) return "pending";
  return "stopped";
}

function terminalDescription(status: InvestigationView["status"], citations: number): string {
  if (status === "COMPLETED") {
    return `Completed with ${citations} persisted citation${citations === 1 ? "" : "s"}.`;
  }
  if (status === "WAITING_FOR_EVIDENCE") return "Waiting for a bounded evidence result.";
  if (status === "ANALYZING") return "A bounded analysis round is in progress.";
  if (status === "CREATED") return "The run is accepted and has not started analysis.";
  return "The run stopped without authorizing any remediation action.";
}
