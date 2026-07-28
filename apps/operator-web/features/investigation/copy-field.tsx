"use client";

import { useId, useRef, useState } from "react";

import styles from "./copy-field.module.css";

interface CopyFieldProps {
  label: string;
  value: string;
}

interface CopyFeedback {
  text: string;
  value: string;
}

export function CopyField({ label, value }: CopyFieldProps) {
  const statusId = useId();
  const latestCopyRequest = useRef(0);
  const [feedback, setFeedback] = useState<CopyFeedback | null>(null);

  async function copy(): Promise<void> {
    const request = latestCopyRequest.current + 1;
    latestCopyRequest.current = request;
    try {
      await navigator.clipboard.writeText(value);
      if (request !== latestCopyRequest.current) return;
      setFeedback((current) => ({
        value,
        text: current?.value === value && current.text === "Copied" ? "Copied again" : "Copied",
      }));
    } catch {
      if (request !== latestCopyRequest.current) return;
      setFeedback((current) => ({
        value,
        text: current?.value === value && current.text === "Copy unavailable"
          ? "Copy still unavailable"
          : "Copy unavailable",
      }));
    }
  }

  return (
    <div className={styles.field}>
      <code>{value}</code>
      <button type="button" onClick={copy} aria-describedby={statusId}>
        Copy {label}
      </button>
      <span
        id={statusId}
        className="sr-only"
        role="status"
        aria-live="polite"
        aria-atomic="true"
      >
        {feedback?.value === value ? feedback.text : ""}
      </span>
    </div>
  );
}
