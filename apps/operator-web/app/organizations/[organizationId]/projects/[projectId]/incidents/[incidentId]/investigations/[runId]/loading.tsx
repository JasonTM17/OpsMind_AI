import { OperatorShell } from "@/features/investigation/operator-shell";

import styles from "./loading.module.css";

export default function InvestigationLoading() {
  return (
    <OperatorShell breadcrumb="Loading authorized investigation">
      <main
        id="main-content"
        className={styles.loading}
        aria-busy="true"
        aria-label="Loading investigation"
      >
        <div className={styles.summary} aria-hidden="true">
          <div className={styles.summaryIdentity}>
            <span />
            <span />
            <span />
          </div>
          <div className={styles.summaryFacts}>
            <span />
            <span />
            <span />
            <span />
            <span />
          </div>
        </div>
        <div className={styles.layout} aria-hidden="true">
          <div className={styles.contextPanel}>
            <span />
            <span />
            <span />
          </div>
          <div className={styles.evidencePanel}>
            <span />
            <span />
            <span />
            <span />
          </div>
          <div className={styles.conclusionPanel}>
            <span />
            <span />
            <span />
          </div>
        </div>
        <p className="sr-only">Loading the authorized investigation projection.</p>
      </main>
    </OperatorShell>
  );
}
