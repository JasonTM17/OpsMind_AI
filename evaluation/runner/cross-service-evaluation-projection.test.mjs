import assert from "node:assert/strict";
import { existsSync, mkdirSync, readFileSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import path from "node:path";
import { test } from "node:test";

import {
  enrichTraceWithEvaluationExports,
  projectCrossServiceEvaluationExport,
} from "./cross-service-evaluation-projection.mjs";
import { runProjectionCli } from "./project-cross-service-evaluation-export.mjs";
import { createEvaluationContractValidator } from "./evaluation-contract-validation.mjs";

const repositoryRoot = path.resolve(import.meta.dirname, "../..");
const exportBytes = readFileSync(new URL(
  "../fixtures/phase-08b-export.scenario-a.regression.json",
  import.meta.url,
));
const baseExport = JSON.parse(exportBytes);
const baseTrace = JSON.parse(readFileSync(new URL(
  "../fixtures/phase-07-trace.scenario-a.valid.json",
  import.meta.url,
)));
const traceReference = "repository://evaluation/fixtures/enriched.json";

function mutated(change) {
  const value = structuredClone(baseExport);
  change(value);
  return Buffer.from(JSON.stringify(value));
}

function rejects(bytes, code) {
  assert.throws(
    () => projectCrossServiceEvaluationExport(bytes),
    (error) => error?.code === code,
  );
}

test("projects a strict digest-bound evaluation export deterministically", () => {
  const validate = createEvaluationContractValidator(repositoryRoot);
  assert.deepEqual(validate(baseExport, "cross-service-evaluation-export.schema.json"), []);
  const first = projectCrossServiceEvaluationExport(exportBytes);
  const second = projectCrossServiceEvaluationExport(exportBytes);
  assert.deepEqual(first, second);
  assert.equal(first.schemaVersion, "opsmind-cross-service-evaluation-projection-v1");
  assert.equal(first.timeline.length, 6);
  assert.equal(first.acceptedAnalyses.length, 2);
  assert.notEqual(
    first.sourceExport.byteDigest.digest,
    first.sourceExport.canonicalDigest.digest,
  );
  assert.equal(first.canonicalDigest.digest_type, "canonical-json");
});

test("rejects duplicate JSON keys before parsing", () => {
  const source = exportBytes.toString("utf8").replace(
    '"schema_version": "opsmind-cross-service-evaluation-export-v1",',
    '"schema_version": "opsmind-cross-service-evaluation-export-v1",'
      + '"schema_version": "opsmind-cross-service-evaluation-export-v1",',
  );
  assert.throws(() => projectCrossServiceEvaluationExport(source), /duplicate JSON key/u);
});

test("rejects malformed UTF-8 bytes", () => {
  const malformed = Buffer.concat([exportBytes.subarray(0, 1), Buffer.from([0xff])]);
  rejects(malformed, "INVALID_BYTES");
});

test("rejects secrets and reasoning inside otherwise valid string fields", () => {
  rejects(mutated((value) => {
    value.events[4].accepted_analysis.hypotheses[0].explanation = "password=super-secret-value";
  }), "UNSAFE_VALUE");
  rejects(mutated((value) => {
    value.events[4].accepted_analysis.hypotheses[0].explanation = "internal reasoning: private";
  }), "UNSAFE_VALUE");
});

test("rejects unknown nested fields and foreign scope", () => {
  rejects(mutated((value) => {
    value.events[0].raw_body = "redacted";
  }), "PROHIBITED_KEY");
  rejects(mutated((value) => {
    value.evidence_records[0].organization_id = "10000000-0000-4000-8000-000000000899";
  }), "FOREIGN_SCOPE");
});

test("rejects event gaps and ambiguous invocation bindings", () => {
  rejects(mutated((value) => {
    value.events[3].sequence_no = 9;
  }), "EVENT_GAP");
  rejects(mutated((value) => {
    value.events[1].accepted_analysis = structuredClone(value.events[4].accepted_analysis);
    const source = value.analysis_invocations[1];
    const target = value.analysis_invocations[0];
    target.response_payload = structuredClone(source.response_payload);
    target.response_status = source.response_status;
    target.model_id = source.model_id;
    target.prompt_version = source.prompt_version;
    target.schema_version = source.schema_version;
    target.actual_tokens = source.actual_tokens;
    target.actual_tools = source.actual_tools;
    target.actual_cost_usd = source.actual_cost_usd;
  }), "INVOCATION_AMBIGUITY");
});

test("rejects connector substitution and receipt drift", () => {
  rejects(mutated((value) => {
    value.tool_receipts[0].connector_id = "substituted";
  }), "CONNECTOR_SUBSTITUTION");
  rejects(mutated((value) => {
    value.tool_receipts[0].audit_request_digest =
      "sha256:9999999999999999999999999999999999999999999999999999999999999999";
  }), "RECEIPT_BINDING");
  rejects(mutated((value) => {
    value.tool_receipts[0].evidence_digests.push(
      value.tool_receipts[0].evidence_digests[0],
    );
  }), "RECEIPT_BINDING");
  rejects(mutated((value) => {
    const changedDigest =
      "sha256:9999999999999999999999999999999999999999999999999999999999999999";
    value.evidence_records[0].content_digest = changedDigest;
    value.tool_receipts[0].evidence_digests[0] = changedDigest;
  }), "RECEIPT_BINDING");
});

test("rejects invocation identity, accounting, and timestamp drift", () => {
  for (const change of [
    (value) => { value.analysis_invocations[0].model_id = "substituted-model"; },
    (value) => { value.analysis_invocations[0].prompt_version = "substituted-prompt"; },
    (value) => { value.analysis_invocations[0].schema_version = "substituted-schema"; },
    (value) => { value.analysis_invocations[0].actual_tokens += 1; },
    (value) => { value.analysis_invocations[0].actual_tools += 1; },
    (value) => { value.analysis_invocations[0].actual_cost_usd = 0.01; },
    (value) => {
      value.analysis_invocations[0].finished_at = "2029-12-31T23:59:59Z";
    },
  ]) {
    rejects(mutated(change), "INVOCATION_BINDING");
  }
});

test("enriches only the exactly matching trace run", () => {
  const enriched = enrichTraceWithEvaluationExports(baseTrace, [exportBytes], traceReference);
  assert.equal(enriched.runs[0].evaluationProjection.timeline.length, 6);
  assert.equal(enriched.runs[0].rawAnalysis.status, "complete");
  assert.equal(enriched.runs[0].toolExecutions[0].connectorId, "prometheus-read-only");
  assert.equal(enriched.runs[0].toolExecutions[0].evidenceBindings.length, 1);
  assert.equal(enriched.runs[0].rawAnalysisReference, `${traceReference}#/runs/0/rawAnalysis`);
  const foreign = structuredClone(baseTrace);
  foreign.runs[0].runId = "10000000-0000-4000-8000-000000000899";
  assert.throws(
    () => enrichTraceWithEvaluationExports(foreign, [exportBytes], traceReference),
    (error) => error?.code === "FOREIGN_SCOPE",
  );
});

test("accepts derived version 8 identities and still rejects malformed ones", () => {
  // Platform evidence and execution identities are RFC 9562 version 8, derived
  // from a domain-separated SHA-256 rather than drawn at random, so a validator
  // limited to versions 1 through 5 rejects every real production export while
  // the version 4 literals in this fixture keep passing.
  const derived = "10000000-0000-8000-8000-000000000813";
  const accepted = projectCrossServiceEvaluationExport(mutated((value) => {
    value.run.evidence_ids = [derived];
    for (const record of value.evidence_records) {
      if (record.evidence_id === "10000000-0000-4000-8000-000000000813") {
        record.evidence_id = derived;
      }
    }
    for (const receipt of value.tool_receipts) {
      if (receipt.evidence_id === "10000000-0000-4000-8000-000000000813") {
        receipt.evidence_id = derived;
      }
    }
    for (const event of value.events) {
      if (event.evidence_id === "10000000-0000-4000-8000-000000000813") {
        event.evidence_id = derived;
      }
    }
  }));
  assert.deepEqual(accepted.run.evidenceIds, [derived]);
  assert.equal(accepted.evidenceRecords[0].evidenceId, derived);

  for (const malformed of [
    "10000000-0000-0000-8000-000000000813",
    "10000000-0000-9000-8000-000000000813",
    "10000000-0000-8000-c000-000000000813",
  ]) {
    rejects(
      mutated((value) => {
        value.run.evidence_ids = [malformed];
      }),
      "INVALID_IDENTITY",
    );
  }
});

test("CLI refuses existing and linked output paths", () => {
  const root = path.join(repositoryRoot, ".opsmind", "reports", `projection-test-${process.pid}`);
  const linked = path.join(root, "linked");
  const target = path.join(root, "target");
  mkdirSync(target, { recursive: true });
  const tracePath = path.join(root, "trace.json");
  const exportPath = path.join(root, "export.json");
  const outputPath = path.join(root, "output.json");
  writeFileSync(tracePath, JSON.stringify(baseTrace));
  writeFileSync(exportPath, exportBytes);
  writeFileSync(outputPath, "{}");
  try {
    assert.throws(
      () => runProjectionCli([
        "--trace", tracePath, "--output", outputPath, "--export", exportPath,
      ]),
      /already exists/u,
    );
    try {
      symlinkSync(target, linked, "junction");
      assert.throws(
        () => runProjectionCli([
          "--trace", tracePath,
          "--output", path.join(linked, "unsafe.json"),
          "--export", exportPath,
        ]),
        /unsafe/u,
      );
    } catch (error) {
      if (!["EPERM", "EACCES"].includes(error?.code)) throw error;
    }
  } finally {
    if (existsSync(root)) rmSync(root, { recursive: true, force: true });
  }
});
