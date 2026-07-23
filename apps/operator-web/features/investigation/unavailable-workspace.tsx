import type { InvestigationWorkspaceResult } from "./investigation-types";
import { OperatorShell } from "./operator-shell";
import styles from "./unavailable-workspace.module.css";

type Unavailable = Extract<InvestigationWorkspaceResult, { kind: "unavailable" }>;

interface UnavailableWorkspaceProps {
  unavailable: Unavailable;
}

export function UnavailableWorkspace({ unavailable }: UnavailableWorkspaceProps) {
  return (
    <OperatorShell>
      <main id="main-content" className={styles.main}>
        <section className={styles.message} aria-labelledby="unavailable-title">
          <p className={styles.kicker}>Fail-closed operator boundary</p>
          <h1 id="unavailable-title">{unavailable.title}</h1>
          <p>{unavailable.detail}</p>
          <dl>
            <div><dt>Browser tool access</dt><dd>Disabled</dd></div>
            <div><dt>Remediation actions</dt><dd>Disabled</dd></div>
            <div><dt>Durable backend state</dt><dd>Unchanged</dd></div>
            <div><dt>Reason</dt><dd>{unavailable.reason}</dd></div>
          </dl>
          {unavailable.correlationId ? (
            <p className={styles.correlation}>
              Support correlation <code>{unavailable.correlationId}</code>
            </p>
          ) : null}
          <a href="">Refresh status</a>
        </section>
        <aside className={styles.boundary} aria-labelledby="boundary-title">
          <p>Security boundary</p>
          <h2 id="boundary-title">No credential fallback</h2>
          <ul>
            <li>Access tokens remain server-side.</li>
            <li>No direct AI, Tool Gateway, or Prometheus call.</li>
            <li>Invalid projections are never partially rendered.</li>
            <li>No background retry hides a dependency failure.</li>
          </ul>
        </aside>
      </main>
    </OperatorShell>
  );
}
