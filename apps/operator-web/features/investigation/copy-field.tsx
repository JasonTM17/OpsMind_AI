"use client";

import { useId, useState } from "react";

import styles from "./copy-field.module.css";

interface CopyFieldProps {
  label: string;
  value: string;
}

export function CopyField({ label, value }: CopyFieldProps) {
  const statusId = useId();
  const [feedback, setFeedback] = useState("");

  async function copy(): Promise<void> {
    try {
      await navigator.clipboard.writeText(value);
      setFeedback("Copied");
    } catch {
      setFeedback("Copy unavailable");
    }
  }

  return (
    <div className={styles.field}>
      <code>{value}</code>
      <button type="button" onClick={copy} aria-describedby={statusId}>
        Copy {label}
      </button>
      <span id={statusId} className="sr-only" aria-live="polite">
        {feedback}
      </span>
    </div>
  );
}
