import fs from "node:fs";
import path from "node:path";

import {
  DuplicateJsonKeyError,
  rejectDuplicateJsonKeys,
} from "./duplicate-json-key-detector.mjs";

export function createContractFileAccess(repositoryRoot, errors) {
  function relativeName(absolutePath) {
    return path.relative(repositoryRoot, absolutePath).replaceAll(path.sep, "/");
  }

  function isWithin(candidatePath, parentPath, allowSame = false) {
    const relativePath = path.relative(parentPath, candidatePath);
    return (allowSame && relativePath === "") || (
      relativePath !== "" && !relativePath.startsWith("..") && !path.isAbsolute(relativePath)
    );
  }

  function hasSymlinkFromRoot(absolutePath) {
    const resolvedPath = path.resolve(absolutePath);
    if (!isWithin(resolvedPath, repositoryRoot, true)) return true;
    const relativePath = path.relative(repositoryRoot, resolvedPath);
    let currentPath = repositoryRoot;
    for (const segment of relativePath.split(path.sep).filter(Boolean)) {
      currentPath = path.join(currentPath, segment);
      if (fs.existsSync(currentPath) && fs.lstatSync(currentPath).isSymbolicLink()) return true;
    }
    return false;
  }

  function readSafeFile(absolutePath) {
    const resolvedPath = path.resolve(absolutePath);
    if (!isWithin(resolvedPath, repositoryRoot)) {
      throw new Error("path escapes the repository root");
    }
    if (hasSymlinkFromRoot(resolvedPath)) {
      throw new Error("path contains a symlink or junction");
    }
    return fs.readFileSync(resolvedPath, "utf8");
  }

  function walkJsonFiles(rootPath) {
    const files = [];
    function visit(directoryPath) {
      if (hasSymlinkFromRoot(directoryPath)) {
        errors.push(`unsafe schema or fixture directory: ${relativeName(directoryPath)}`);
        return;
      }
      for (const entry of fs.readdirSync(directoryPath, { withFileTypes: true })) {
        const entryPath = path.join(directoryPath, entry.name);
        if (entry.isSymbolicLink()) {
          errors.push(`symlinked schema or fixture entry: ${relativeName(entryPath)}`);
        } else if (entry.isDirectory()) {
          visit(entryPath);
        } else if (entry.isFile() && entry.name.endsWith(".json")) {
          files.push(entryPath);
        }
      }
    }
    visit(rootPath);
    return files.sort((left, right) => left.localeCompare(right));
  }

  function parseJsonDocuments(filePaths) {
    const documents = new Map();
    for (const filePath of filePaths) {
      try {
        const source = readSafeFile(filePath);
        rejectDuplicateJsonKeys(source);
        const parsed = JSON.parse(source);
        if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
          errors.push(`JSON root must be an object: ${relativeName(filePath)}`);
        } else {
          documents.set(path.resolve(filePath), parsed);
        }
      } catch (error) {
        const reason = error instanceof DuplicateJsonKeyError
          ? "duplicate JSON key"
          : "invalid or unsafe JSON";
        errors.push(`${reason}: ${relativeName(filePath)}`);
      }
    }
    return documents;
  }

  return {
    hasSymlinkFromRoot,
    isWithin,
    parseJsonDocuments,
    readSafeFile,
    relativeName,
    walkJsonFiles,
  };
}
