import path from "node:path";

import { createContractFileAccess } from "../../scripts/validation/phase-04-incident-contracts/safe-contract-files.mjs";
import { createLocalReferenceResolver } from "../../scripts/validation/phase-04-incident-contracts/local-reference-resolver.mjs";
import { createSubsetValidator } from "../../scripts/validation/phase-04-incident-contracts/subset-json-schema-validator.mjs";

export function createEvaluationContractValidator(repositoryRoot) {
  const errors = [];
  const access = createContractFileAccess(repositoryRoot, errors);
  const schemaRoot = path.join(repositoryRoot, "evaluation", "schemas");
  const schemaFiles = access.walkJsonFiles(schemaRoot);
  const documents = access.parseJsonDocuments(schemaFiles);
  if (errors.length > 0) {
    throw new Error(`Evaluation schema loading failed: ${errors.join("; ")}`);
  }
  const resolveLocalReference = createLocalReferenceResolver({
    contractsRoot: schemaRoot,
    documents,
    hasSymlinkFromRoot: access.hasSymlinkFromRoot,
    isWithin: access.isWithin,
  });
  const validate = createSubsetValidator(resolveLocalReference);

  return function validateDocument(document, schemaName) {
    const schemaPath = path.resolve(schemaRoot, schemaName);
    const schema = documents.get(schemaPath);
    if (!schema) throw new Error(`Evaluation schema is missing: ${schemaName}`);
    return validate(document, schema, schemaPath);
  };
}
