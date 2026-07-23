import type { ReactNode } from "react";
import Link from "next/link";

import { operatorEnvironmentLabel } from "@/lib/operator-environment";

import styles from "./operator-shell.module.css";

interface OperatorShellProps {
  breadcrumb?: string;
  children: ReactNode;
  sessionState?: "verified-read-only" | "not-asserted";
}

export function OperatorShell({
  breadcrumb = "Operator workspace",
  children,
  sessionState = "not-asserted",
}: OperatorShellProps) {
  const environment = operatorEnvironmentLabel();
  const verified = sessionState === "verified-read-only";

  return (
    <div className={styles.shell}>
      <a className="skip-link" href="#main-content">Skip to investigation content</a>
      <header className={styles.topbar}>
        <Link className={styles.wordmark} href="/" aria-label="OpsMind AI home">
          <span aria-hidden="true" />
          OpsMind <strong>AI</strong>
        </Link>
        <span className={styles.environment}>{environment}</span>
        <p className={styles.breadcrumb}>{breadcrumb}</p>
        <div
          className={styles.health}
          data-state={sessionState}
          aria-label={verified ? "Authorized read-only session" : "Session status not asserted"}
        >
          <span aria-hidden="true" />
          {verified ? "Authorized read-only" : "Session not asserted"}
        </div>
        <div
          className={styles.operator}
          aria-label={verified ? "Authorized operator scope" : "Operator identity unavailable"}
        >
          <span aria-hidden="true">{verified ? "RO" : "NA"}</span>
          <span>{verified ? "Authorized scope" : "Identity unavailable"}</span>
        </div>
      </header>
      {children}
    </div>
  );
}
