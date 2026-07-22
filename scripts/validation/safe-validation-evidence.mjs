import fs from "node:fs";
import path from "node:path";

function isPathWithin(candidatePath, parentPath) {
  const relativePath = path.relative(parentPath, candidatePath);
  return relativePath !== "" && !relativePath.startsWith("..") && !path.isAbsolute(relativePath);
}

function containsSymlink(candidatePath) {
  let currentPath = path.resolve(candidatePath);
  while (true) {
    if (fs.existsSync(currentPath) && fs.lstatSync(currentPath).isSymbolicLink()) return true;
    const parentPath = path.dirname(currentPath);
    if (parentPath === currentPath) return false;
    currentPath = parentPath;
  }
}

export function prepareValidationEvidence({
  repositoryRoot,
  configuredArtifactRoot,
  configuredEvidencePath,
  defaultRelativePath,
}) {
  if (configuredArtifactRoot && !path.isAbsolute(configuredArtifactRoot)) {
    return { error: "OPS_ARTIFACT_ROOT must be absolute", evidencePath: null };
  }

  const artifactRoot = path.resolve(configuredArtifactRoot || path.join(repositoryRoot, "artifacts"));
  const repositoryPath = path.resolve(repositoryRoot);
  if (
    artifactRoot === repositoryPath ||
    isPathWithin(repositoryPath, artifactRoot) ||
    artifactRoot === path.parse(artifactRoot).root
  ) {
    return { error: "OPS_ARTIFACT_ROOT is an unsafe repository ancestor or volume root", evidencePath: null };
  }
  if (!fs.existsSync(artifactRoot) || !fs.lstatSync(artifactRoot).isDirectory()) {
    return { error: null, evidencePath: null, reason: "ARTIFACT_ROOT_UNAVAILABLE" };
  }
  if (containsSymlink(artifactRoot)) {
    return { error: "OPS_ARTIFACT_ROOT contains a symlink or junction", evidencePath: null };
  }

  if (configuredEvidencePath && !path.isAbsolute(configuredEvidencePath)) {
    return { error: "OPS_LAYOUT_EVIDENCE_PATH must be absolute", evidencePath: null };
  }
  const evidencePath = path.resolve(
    configuredEvidencePath || path.join(artifactRoot, defaultRelativePath),
  );
  if (!isPathWithin(evidencePath, artifactRoot)) {
    return { error: "OPS_LAYOUT_EVIDENCE_PATH must remain beneath OPS_ARTIFACT_ROOT", evidencePath: null };
  }

  const evidenceDirectory = path.dirname(evidencePath);
  if (containsSymlink(evidenceDirectory)) {
    return { error: "Evidence directory contains a symlink or junction", evidencePath: null };
  }
  fs.mkdirSync(evidenceDirectory, { recursive: true });
  if (containsSymlink(evidenceDirectory)) {
    return { error: "Evidence directory became symlinked during creation", evidencePath: null };
  }
  return { error: null, evidencePath, reason: "FILE" };
}

export function publishValidationEvidence(evidencePath, transcript) {
  const temporaryPath = `${evidencePath}.${process.pid}.${Date.now()}.tmp`;
  try {
    fs.writeFileSync(temporaryPath, transcript, { encoding: "utf8", flag: "wx" });
    fs.renameSync(temporaryPath, evidencePath);
  } finally {
    if (fs.existsSync(temporaryPath)) fs.unlinkSync(temporaryPath);
  }
}
