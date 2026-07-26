import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { existsSync, mkdirSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import path from "node:path";
import { test } from "node:test";

import { PAYLOAD_ROOT_ENVIRONMENT, resolveHeldOutCorpus } from "./held-out-corpus.mjs";

const repositoryRoot = path.resolve(import.meta.dirname, "../..");
const familyIds = new Set(["SIM-01", "SIM-02", "SIM-03"]);

function payload(body) {
  const bytes = Buffer.from(JSON.stringify(body));
  return { bytes, digest: `sha256:${createHash("sha256").update(bytes).digest("hex")}` };
}

function manifest(cases) {
  return Buffer.from(JSON.stringify({
    schema_version: "opsmind-held-out-manifest-v1",
    corpus_id: "phase-08-held-out",
    version: "0.1.0",
    payload_root_environment: PAYLOAD_ROOT_ENVIRONMENT,
    cases,
  }));
}

function entry(overrides = {}) {
  return {
    case_id: "case-one",
    family_id: "SIM-01",
    relative_path: "case-one.json",
    content_digest: "sha256:".padEnd(71, "0"),
    byte_size: 1,
    added_at: "2026-07-26T00:00:00Z",
    contamination_tag: "never-trained",
    ...overrides,
  };
}

function withRoot(run) {
  const root = path.join(repositoryRoot, ".opsmind", "held-out-tests", `corpus-${process.pid}`);
  mkdirSync(root, { recursive: true });
  try {
    return run(root);
  } finally {
    if (existsSync(root)) rmSync(root, { recursive: true, force: true });
  }
}

function rejects(bytes, root, code) {
  assert.throws(
    () => resolveHeldOutCorpus({
      manifestBytes: bytes,
      payloadRoot: root,
      knownFamilyIds: familyIds,
    }),
    (error) => error?.code === code,
  );
}

test("reports absence rather than success when nothing is configured", () => {
  // An empty corpus and an unset root are both absences of evidence. Reporting
  // either as a pass would let zero held-out cases read as full coverage.
  const empty = resolveHeldOutCorpus({
    manifestBytes: manifest([]),
    payloadRoot: "",
    knownFamilyIds: familyIds,
  });
  assert.equal(empty.status, "UNAVAILABLE");
  assert.match(empty.reason, /no held-out cases/u);
  assert.deepEqual(empty.cases, []);

  const unset = resolveHeldOutCorpus({
    manifestBytes: manifest([entry()]),
    payloadRoot: "   ",
    knownFamilyIds: familyIds,
  });
  assert.equal(unset.status, "UNAVAILABLE");
  assert.match(unset.reason, new RegExp(PAYLOAD_ROOT_ENVIRONMENT, "u"));
});

test("resolves a registered case and excludes quarantined ones", () => {
  withRoot((root) => {
    const first = payload({ case: "one" });
    const second = payload({ case: "two" });
    writeFileSync(path.join(root, "case-one.json"), first.bytes);
    writeFileSync(path.join(root, "case-two.json"), second.bytes);
    const resolved = resolveHeldOutCorpus({
      manifestBytes: manifest([
        entry({ content_digest: first.digest, byte_size: first.bytes.length }),
        entry({
          case_id: "case-two",
          family_id: "SIM-02",
          relative_path: "case-two.json",
          content_digest: second.digest,
          byte_size: second.bytes.length,
          contamination_tag: "quarantined",
        }),
      ]),
      payloadRoot: root,
      knownFamilyIds: familyIds,
    });
    assert.equal(resolved.status, "RESOLVED");
    assert.equal(resolved.quarantined, 1);
    assert.deepEqual(resolved.cases.map((item) => item.caseId), ["case-one"]);
  });
});

test("fails closed when a registered payload drifts or disappears", () => {
  withRoot((root) => {
    const original = payload({ case: "one" });
    const casePath = path.join(root, "case-one.json");
    const registered = entry({
      content_digest: original.digest,
      byte_size: original.bytes.length,
    });

    rejects(manifest([registered]), root, "HELD_OUT_PAYLOAD");

    writeFileSync(casePath, Buffer.from(JSON.stringify({ case: "tampered" })));
    rejects(manifest([registered]), root, "HELD_OUT_PAYLOAD");

    writeFileSync(casePath, original.bytes);
    rejects(
      manifest([entry({ ...registered, byte_size: original.bytes.length + 1 })]),
      root,
      "HELD_OUT_PAYLOAD",
    );
  });
});

test("refuses paths that escape the root or arrive through a link", () => {
  withRoot((root) => {
    const outside = path.join(root, "..", `outside-${process.pid}.json`);
    const body = payload({ case: "outside" });
    writeFileSync(outside, body.bytes);
    try {
      // The contract refuses a traversing path before any filesystem work, so
      // this reports a manifest violation rather than a path violation. The
      // containment check below is the second layer, exercised by a path the
      // contract accepts.
      rejects(
        manifest([entry({
          relative_path: `../outside-${process.pid}.json`,
          content_digest: body.digest,
          byte_size: body.bytes.length,
        })]),
        root,
        "HELD_OUT_MANIFEST",
      );

      const target = path.join(root, "target");
      mkdirSync(target, { recursive: true });
      writeFileSync(path.join(target, "case-one.json"), body.bytes);
      try {
        symlinkSync(target, path.join(root, "linked"), "junction");
      } catch (error) {
        if (["EPERM", "EACCES"].includes(error?.code)) return;
        throw error;
      }
      rejects(
        manifest([entry({
          relative_path: "linked/case-one.json",
          content_digest: body.digest,
          byte_size: body.bytes.length,
        })]),
        root,
        "HELD_OUT_PATH",
      );
    } finally {
      if (existsSync(outside)) rmSync(outside, { force: true });
    }
  });
});

test("refuses a corpus that counts one observation more than once", () => {
  // The statistical protocol treats a case as one observation and refuses to
  // let correlated repeats grow the denominator. Distinct identifiers over the
  // same content would do exactly that at the corpus level, which is the same
  // inflation one scenario replayed a hundred times would produce.
  const shared = payload({ case: "same" });
  const first = entry({
    content_digest: shared.digest,
    byte_size: shared.bytes.length,
  });
  rejects(
    manifest([first, { ...first, case_id: "case-two", relative_path: "case-two.json" }]),
    "",
    "HELD_OUT_MANIFEST",
  );

  const other = payload({ case: "other" });
  rejects(
    manifest([
      first,
      {
        ...first,
        case_id: "case-two",
        content_digest: other.digest,
        byte_size: other.bytes.length,
      },
    ]),
    "",
    "HELD_OUT_MANIFEST",
  );
});

test("rejects a malformed manifest before touching the filesystem", () => {
  rejects(
    Buffer.from(JSON.stringify({
      schema_version: "opsmind-held-out-manifest-v2",
      corpus_id: "phase-08-held-out",
      version: "0.1.0",
      payload_root_environment: PAYLOAD_ROOT_ENVIRONMENT,
      cases: [],
    })),
    "",
    "HELD_OUT_MANIFEST",
  );
  rejects(manifest([entry(), entry()]), "", "HELD_OUT_MANIFEST");
  rejects(manifest([entry({ family_id: "SIM-99" })]), "", "HELD_OUT_MANIFEST");
});
