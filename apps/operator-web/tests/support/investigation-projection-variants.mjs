import { completed } from "./investigation-fixtures.mjs";

export function completedVariant(runId, analysisOverrides = {}, rootOverrides = {}) {
  return {
    ...completed,
    ...rootOverrides,
    runId,
    analysis: {
      ...completed.analysis,
      ...analysisOverrides,
      run_id: runId,
    },
  };
}

export function longContentVariant(runId) {
  const evidenceIds = [
    completed.evidenceIds[0],
    ...Array.from({ length: 199 }, (_, index) =>
      `10000000-0000-4000-8001-${String(index + 1).padStart(12, "0")}`),
  ];
  const citations = Array.from({ length: 100 }, (_, index) => ({
    evidence_id: evidenceIds[index],
    digest: `sha256:${index.toString(16).padStart(64, "0")}`,
    claim: index === 0 ? "C".repeat(900) : `Bounded evidence claim ${index + 1}.`,
  }));
  return completedVariant(runId, {
    model_id: `model-${"m".repeat(120)}`,
    prompt_version: `prompt-${"p".repeat(110)}`,
    hypotheses: [{
      ...completed.analysis.hypotheses[0],
      title: "UnbrokenSignal".repeat(15),
      explanation: "E".repeat(2_000),
      citations: citations.slice(0, 50),
    }, {
      ...completed.analysis.hypotheses[1],
      citations: citations.slice(50),
    }],
    citations,
  }, {
    budget: { ...completed.budget, maxEvidenceItems: 200 },
    evidenceIds,
  });
}
