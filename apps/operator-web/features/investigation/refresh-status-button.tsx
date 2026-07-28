"use client";

import { useRouter } from "next/navigation";
import { useEffect, useId, useRef, useState, useTransition } from "react";

import styles from "./refresh-status-button.module.css";

interface RefreshStatusButtonProps {
  emphasis?: "primary" | "secondary";
}

export function RefreshStatusButton({
  emphasis = "secondary",
}: RefreshStatusButtonProps) {
  const router = useRouter();
  const statusId = useId();
  const completionCount = useRef(0);
  const refreshStarted = useRef(false);
  const [feedback, setFeedback] = useState("");
  const [isPending, startTransition] = useTransition();

  useEffect(() => {
    if (isPending || !refreshStarted.current) return;

    refreshStarted.current = false;
    completionCount.current += 1;
    setFeedback(completionCount.current > 1 ? "Status refreshed again." : "Status refreshed.");
  }, [isPending]);

  function refresh(): void {
    refreshStarted.current = true;
    setFeedback("Refreshing status.");
    startTransition(() => router.refresh());
  }

  return (
    <>
      <button
        aria-describedby={statusId}
        className={styles.button}
        data-emphasis={emphasis}
        disabled={isPending}
        type="button"
        onClick={refresh}
      >
        {isPending ? "Refreshing status" : "Refresh status"}
      </button>
      <span
        id={statusId}
        className="sr-only"
        role="status"
        aria-label="Refresh status result"
        aria-live="polite"
        aria-atomic="true"
      >
        {feedback}
      </span>
    </>
  );
}
