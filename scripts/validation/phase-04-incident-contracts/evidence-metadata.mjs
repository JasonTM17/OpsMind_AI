import { spawnSync } from "node:child_process";
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";

function run(repositoryRoot, executable, args) {
  const result = spawnSync(executable, args, {
    cwd: repositoryRoot,
    encoding: "utf8",
    timeout: 5_000,
    windowsHide: true,
  });
  return result.status === 0 ? result.stdout.trim() : null;
}

function walkFiles(root, accept) {
  if (!fs.existsSync(root)) return [];
  const files = [];
  const pending = [root];
  while (pending.length > 0) {
    const current = pending.pop();
    const stat = fs.lstatSync(current);
    if (stat.isSymbolicLink()) throw new Error("evidence input contains a symlink");
    if (stat.isDirectory()) {
      for (const name of fs.readdirSync(current)) pending.push(path.join(current, name));
    } else if (stat.isFile() && accept(current)) {
      files.push(current);
    }
  }
  return files;
}

function sha256File(file) {
  return crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
}

function manifestDigest(repositoryRoot, inputFiles) {
  const repositoryPath = path.resolve(repositoryRoot);
  const files = [...new Set(inputFiles.map((file) => path.resolve(file)))].sort();
  const digest = crypto.createHash("sha256");
  for (const file of files) {
    const relative = path.relative(repositoryPath, file).replaceAll("\\", "/");
    if (!relative || relative.startsWith("../") || path.isAbsolute(relative)) {
      throw new Error("evidence input escapes the repository");
    }
    const stat = fs.lstatSync(file);
    if (!stat.isFile() || stat.isSymbolicLink()) {
      throw new Error("evidence input is not a regular repository file");
    }
    digest.update(relative, "utf8");
    digest.update("\0");
    digest.update(fs.readFileSync(file));
    digest.update("\0");
  }
  return { count: files.length, sha256: digest.digest("hex") };
}

export function createPhase4EvidenceMetadata({
  repositoryRoot,
  contractFiles,
  migrationPath,
  startedAt,
}) {
  const completedAt = new Date();
  const revision = run(repositoryRoot, "git", ["rev-parse", "--verify", "HEAD"]);
  const status = run(repositoryRoot, "git", ["status", "--porcelain", "--untracked-files=normal"]);
  const gitVersion = run(repositoryRoot, "git", ["--version"]) ?? "UNAVAILABLE";
  const sourceFiles = [
    path.join(repositoryRoot, "services", "platform-api", "pom.xml"),
    path.join(repositoryRoot, "services", "platform-api", "src", "main", "resources", "application.yaml"),
    ...walkFiles(
      path.join(repositoryRoot, "services", "platform-api", "src", "main", "java"),
      (file) => file.endsWith(".java"),
    ),
  ];
  const validatorFiles = [
    path.join(repositoryRoot, "scripts", "validation", "validate-phase-04-incident-contracts.mjs"),
    path.join(repositoryRoot, "scripts", "validation", "safe-validation-evidence.mjs"),
    ...walkFiles(
      path.join(repositoryRoot, "scripts", "validation", "phase-04-incident-contracts"),
      (file) => file.endsWith(".mjs"),
    ),
  ];
  const source = manifestDigest(repositoryRoot, sourceFiles);
  const contracts = manifestDigest(repositoryRoot, contractFiles);
  const validators = manifestDigest(repositoryRoot, validatorFiles);

  return [
    "EvidenceSchemaVersion=phase4-static-contract-v2",
    "ReleaseEvidence=NO",
    "PlatformJarSha256=NOT_APPLICABLE_STATIC_GATE",
    `StartedAtUtc=${startedAt.toISOString()}`,
    `CompletedAtUtc=${completedAt.toISOString()}`,
    `DurationMilliseconds=${completedAt.getTime() - startedAt.getTime()}`,
    `CodeRevision=${revision?.toLowerCase() ?? "UNBORN"}`,
    `WorkspaceDirty=${status === null ? "UNKNOWN" : status.length > 0 ? "YES" : "NO"}`,
    `NodeVersion=${process.version}`,
    `GitVersion=${gitVersion.replaceAll(/\s+/gu, "_")}`,
    "Command=node scripts/validation/validate-phase-04-incident-contracts.mjs",
    `SourceFilesHashed=${source.count}`,
    `SourceManifestSha256=${source.sha256}`,
    `ContractFilesHashed=${contracts.count}`,
    `ContractManifestSha256=${contracts.sha256}`,
    `ValidatorFilesHashed=${validators.count}`,
    `ValidatorManifestSha256=${validators.sha256}`,
    `MigrationV003Sha256=${sha256File(migrationPath)}`,
  ];
}
