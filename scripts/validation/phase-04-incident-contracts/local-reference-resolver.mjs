import path from "node:path";

function decodePointerToken(token) {
  return decodeURIComponent(token).replaceAll("~1", "/").replaceAll("~0", "~");
}

function resolveJsonPointer(document, fragment) {
  if (fragment === "" || fragment === "#") return document;
  if (!fragment.startsWith("#/")) return undefined;
  let current = document;
  for (const token of fragment.slice(2).split("/").map(decodePointerToken)) {
    if (typeof current !== "object" || current === null || !Object.hasOwn(current, token)) {
      return undefined;
    }
    current = current[token];
  }
  return current;
}

export function createLocalReferenceResolver({
  contractsRoot,
  documents,
  hasSymlinkFromRoot,
  isWithin,
}) {
  return function resolveLocalReference(ownerPath, reference) {
    if (
      typeof reference !== "string" ||
      /^(?:[A-Za-z][A-Za-z0-9+.-]*:|\/\/)/u.test(reference)
    ) {
      throw new Error("remote reference is not allowed");
    }
    const hashIndex = reference.indexOf("#");
    const fileReference = hashIndex === -1 ? reference : reference.slice(0, hashIndex);
    const fragment = hashIndex === -1 ? "" : reference.slice(hashIndex);
    if (fileReference.includes("%") || path.isAbsolute(fileReference)) {
      throw new Error("encoded or absolute reference is not allowed");
    }
    const targetPath = fileReference === ""
      ? path.resolve(ownerPath)
      : path.resolve(path.dirname(ownerPath), fileReference);
    if (!isWithin(targetPath, contractsRoot) || hasSymlinkFromRoot(targetPath)) {
      throw new Error("reference escapes the contract root or contains a symlink");
    }
    const document = documents.get(targetPath);
    if (!document) throw new Error("reference target does not exist or is not JSON");
    const target = resolveJsonPointer(document, fragment);
    if (target === undefined) throw new Error("reference fragment does not exist");
    return { targetPath, target };
  };
}

export function countAndValidateSchemaReferences({
  documents,
  errors,
  relativeName,
  resolveLocalReference,
  schemaFiles,
}) {
  let referenceCount = 0;

  function visit(node, ownerPath) {
    if (Array.isArray(node)) {
      for (const item of node) visit(item, ownerPath);
      return;
    }
    if (typeof node !== "object" || node === null) return;
    if (Object.hasOwn(node, "$ref")) {
      try {
        resolveLocalReference(ownerPath, node.$ref);
        referenceCount += 1;
      } catch {
        errors.push(`invalid local JSON Schema reference: ${relativeName(ownerPath)}`);
      }
    }
    for (const value of Object.values(node)) visit(value, ownerPath);
  }

  for (const schemaPath of schemaFiles) {
    const schema = documents.get(schemaPath);
    if (schema) visit(schema, schemaPath);
  }
  return referenceCount;
}
