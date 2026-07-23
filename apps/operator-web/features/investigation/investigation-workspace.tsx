import { CitedConclusion } from "./cited-conclusion";
import { EvidenceSpine } from "./evidence-spine";
import { formatUtc, statusLabel } from "./format-investigation-values";
import { IncidentSummary } from "./incident-summary";
import { InvestigationContext } from "./investigation-context";
import type { InvestigationWorkspaceData } from "./investigation-types";
import { OperatorShell } from "./operator-shell";
import styles from "./investigation-workspace.module.css";

interface InvestigationWorkspaceProps {
  data: InvestigationWorkspaceData;
}

export function InvestigationWorkspace({ data }: InvestigationWorkspaceProps) {
  const { incident, investigation } = data;
  const stopped = ["ABSTAINED", "BUDGET_EXCEEDED", "NO_PROGRESS", "FAILED"]
    .includes(investigation.status);

  return (
    <OperatorShell
      breadcrumb={`${incident.organizationId} / ${incident.projectId}`}
      sessionState="verified-read-only"
    >
      <IncidentSummary
        incident={incident}
        investigation={investigation}
        refreshedAt={data.refreshedAt}
      />
      {stopped ? (
        <div className={styles.degraded} role="status">
          <strong>{statusLabel(investigation.status)}</strong>
          <span>
            {investigation.terminalReason ??
              "The investigation stopped safely. Durable state is unchanged and no write was attempted."}
          </span>
          <a href="">Refresh status</a>
        </div>
      ) : null}
      <main id="main-content" className={styles.workspace}>
        <InvestigationContext investigation={investigation} />
        <EvidenceSpine investigation={investigation} />
        <CitedConclusion
          investigation={investigation}
          correlationId={data.correlationId}
          projectionSafety={data.projectionSafety}
        />
      </main>
      <footer className={styles.footer}>
        <span>
          Read model refreshed{" "}
          <time dateTime={data.refreshedAt}>{formatUtc(data.refreshedAt)}</time>
        </span>
        <span>Data residency: environment policy</span>
        <span>Keyboard: Tab to move · Enter to activate</span>
      </footer>
    </OperatorShell>
  );
}
