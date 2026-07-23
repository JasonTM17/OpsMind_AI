import type { IncidentView, InvestigationView } from "./investigation-types";
import { formatDuration, formatUtc, statusLabel } from "./format-investigation-values";
import styles from "./incident-summary.module.css";

interface IncidentSummaryProps {
  incident: IncidentView;
  investigation: InvestigationView;
  refreshedAt: string;
}

export function IncidentSummary({
  incident,
  investigation,
  refreshedAt,
}: IncidentSummaryProps) {
  const elapsedUntil = investigation.endedAt ?? refreshedAt;
  return (
    <header className={styles.summary}>
      <div className={styles.identity}>
        <p className={styles.kicker}>Incident / {incident.id}</p>
        <h1>{incident.title}</h1>
        <p className={styles.description}>{incident.summary}</p>
      </div>
      <dl className={styles.facts}>
        <div>
          <dt>Severity</dt>
          <dd><span className={styles.severity}>{incident.severity}</span></dd>
        </div>
        <div>
          <dt>Incident</dt>
          <dd>{statusLabel(incident.status)}</dd>
        </div>
        <div>
          <dt>Investigation</dt>
          <dd>{statusLabel(investigation.status)}</dd>
        </div>
        <div>
          <dt>Started</dt>
          <dd><time dateTime={investigation.startedAt}>{formatUtc(investigation.startedAt)}</time></dd>
        </div>
        <div>
          <dt>Elapsed</dt>
          <dd>{formatDuration(investigation.startedAt, elapsedUntil)}</dd>
        </div>
      </dl>
    </header>
  );
}
