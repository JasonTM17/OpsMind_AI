import path from "node:path";

const DIALECT = "https://json-schema.org/draft/2020-12/schema";
const ALLOWED_KEYWORDS = new Set([
  "$schema", "$id", "$ref", "$defs", "title", "description", "type", "enum", "const",
  "additionalProperties", "required", "properties", "items", "minItems", "maxItems",
  "uniqueItems", "minLength", "maxLength", "pattern", "format", "minimum", "maximum",
  "allOf", "oneOf", "anyOf", "not", "if", "then", "else", "default",
]);
const ALLOWED_TYPES = new Set([
  "object", "array", "string", "integer", "number", "boolean", "null",
]);

function inspectSchema(schema, filePath, relativeName, errors, location = "$") {
  if (typeof schema === "boolean") return;
  if (typeof schema !== "object" || schema === null || Array.isArray(schema)) {
    errors.push(`schema node is not an object: ${relativeName(filePath)} ${location}`);
    return;
  }
  for (const keyword of Object.keys(schema)) {
    if (!ALLOWED_KEYWORDS.has(keyword)) {
      errors.push(`unsupported JSON Schema keyword: ${relativeName(filePath)} ${location}`);
    }
  }
  const declaredTypes = Array.isArray(schema.type) ? schema.type : schema.type ? [schema.type] : [];
  if (declaredTypes.some((type) => !ALLOWED_TYPES.has(type))) {
    errors.push(`unsupported JSON Schema type: ${relativeName(filePath)} ${location}`);
  }
  if (declaredTypes.includes("object") && schema.additionalProperties !== false) {
    errors.push(`object schema must reject additional properties: ${relativeName(filePath)} ${location}`);
  }
  if (declaredTypes.includes("array") && !Number.isInteger(schema.maxItems)) {
    errors.push(`array schema must have maxItems: ${relativeName(filePath)} ${location}`);
  }
  if (
    declaredTypes.includes("string") &&
    !Object.hasOwn(schema, "$ref") &&
    !Array.isArray(schema.enum) &&
    !Object.hasOwn(schema, "const") &&
    !Number.isInteger(schema.maxLength)
  ) {
    errors.push(`string schema must have maxLength: ${relativeName(filePath)} ${location}`);
  }
  for (const [name, child] of Object.entries(schema.properties ?? {})) {
    inspectSchema(child, filePath, relativeName, errors, `${location}.properties.${name}`);
  }
  for (const [name, child] of Object.entries(schema.$defs ?? {})) {
    inspectSchema(child, filePath, relativeName, errors, `${location}.$defs.${name}`);
  }
  if (schema.items) inspectSchema(schema.items, filePath, relativeName, errors, `${location}.items`);
  for (const keyword of ["allOf", "oneOf", "anyOf"]) {
    schema[keyword]?.forEach((child, index) => {
      inspectSchema(child, filePath, relativeName, errors, `${location}.${keyword}[${index}]`);
    });
  }
  for (const keyword of ["not", "if", "then", "else"]) {
    if (schema[keyword]) {
      inspectSchema(schema[keyword], filePath, relativeName, errors, `${location}.${keyword}`);
    }
  }
}

export function inspectIncidentSchemas({
  documents,
  errors,
  relativeName,
  repositoryRoot,
  requiredSchemaPaths,
  schemaRoot,
}) {
  const schemaIds = new Set();
  for (const relativePath of requiredSchemaPaths) {
    const filePath = path.join(repositoryRoot, ...relativePath.split("/"));
    const schema = documents.get(filePath);
    if (!schema) {
      errors.push(`missing required incident schema: ${relativePath}`);
      continue;
    }
    if (schema.$schema !== DIALECT) {
      errors.push(`schema dialect must be Draft 2020-12: ${relativePath}`);
    }
    if (typeof schema.$id !== "string" || schemaIds.has(schema.$id)) {
      errors.push(`schema identifier is missing or duplicated: ${relativePath}`);
    } else {
      schemaIds.add(schema.$id);
    }
    inspectSchema(schema, filePath, relativeName, errors);
  }

  const incidentTypes = documents.get(
    path.join(schemaRoot, "incidents", "incident-types.schema.json"),
  );
  const severities = ["SEV1", "SEV2", "SEV3", "SEV4"];
  const statuses = [
    "OPEN", "INVESTIGATING", "AWAITING_APPROVAL", "MITIGATING", "RESOLVED", "CLOSED",
  ];
  if (JSON.stringify(incidentTypes?.$defs?.severity?.enum) !== JSON.stringify(severities)) {
    errors.push("incident severity vocabulary is not exact");
  }
  if (JSON.stringify(incidentTypes?.$defs?.status?.enum) !== JSON.stringify(statuses)) {
    errors.push("incident status vocabulary is not exact");
  }

  const auditSchema = documents.get(
    path.join(schemaRoot, "audit", "audit-event.schema.json"),
  );
  const auditRequired = new Set(auditSchema?.required ?? []);
  for (const field of ["schemaVersion", "tenantSequenceNo", "previousDigest", "eventDigest"]) {
    if (!auditRequired.has(field)) errors.push(`audit schema does not require ${field}`);
  }
  if (auditSchema?.properties?.schemaVersion?.const !== "incident-audit-v1") {
    errors.push("audit schema version is not pinned");
  }
  if (Object.hasOwn(auditSchema?.properties ?? {}, "sequence")
      || Object.hasOwn(auditSchema?.properties ?? {}, "sequenceNo")) {
    errors.push("audit schema exposes a database-global sequence");
  }
}
