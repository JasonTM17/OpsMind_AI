import path from "node:path";

export const fixtureCases = [
  ["create-incident-request.valid.json", "incidents/create-incident-request.schema.json", true],
  ["create-incident-request.authority-field.invalid.json", "incidents/create-incident-request.schema.json", false],
  ["create-incident-request.severity.invalid.json", "incidents/create-incident-request.schema.json", false],
  ["transition-incident-request.resolved.valid.json", "incidents/transition-incident-request.schema.json", true],
  ["transition-incident-request.missing-resolution.invalid.json", "incidents/transition-incident-request.schema.json", false],
  ["transition-incident-request.non-resolve-fields.invalid.json", "incidents/transition-incident-request.schema.json", false],
  ["incident.valid.json", "incidents/incident.schema.json", true],
  ["incident.resolved-fields.invalid.json", "incidents/incident.schema.json", false],
  ["incident-timeline-page.valid.json", "incidents/incident-timeline-page.schema.json", true],
  ["incident-timeline-page.event-kind.invalid.json", "incidents/incident-timeline-page.schema.json", false],
  ["audit-event.valid.json", "audit/audit-event.schema.json", true],
  ["audit-event.forged-field.invalid.json", "audit/audit-event.schema.json", false],
];

export function validateFixtureCases({
  documents,
  errors,
  fixtureFiles,
  fixtureRoot,
  schemaRoot,
  validateInstance,
}) {
  const expectedFixturePaths = new Set(
    fixtureCases.map(([fixture]) => path.join(fixtureRoot, "incidents", fixture)),
  );
  const incidentFixtureRoot = path.join(fixtureRoot, "incidents");
  const actualIncidentFixtures = fixtureFiles.filter(
    (filePath) => path.dirname(filePath) === incidentFixtureRoot,
  );
  for (const filePath of actualIncidentFixtures) {
    if (!expectedFixturePaths.has(filePath)) {
      errors.push(`incident fixture is not assigned to a validation case: ${path.basename(filePath)}`);
    }
  }
  for (const [fixtureName, schemaName, shouldPass] of fixtureCases) {
    const fixturePath = path.join(incidentFixtureRoot, fixtureName);
    const schemaPath = path.join(schemaRoot, ...schemaName.split("/"));
    const fixture = documents.get(fixturePath);
    const schema = documents.get(schemaPath);
    if (!fixture || !schema) {
      errors.push(`fixture case input is missing: ${fixtureName}`);
      continue;
    }
    const findings = validateInstance(fixture, schema, schemaPath);
    if (shouldPass && findings.length > 0) {
      errors.push(`positive fixture failed validation: ${fixtureName}`);
    }
    if (!shouldPass && findings.length === 0) {
      errors.push(`negative fixture unexpectedly passed validation: ${fixtureName}`);
    }
  }
}
