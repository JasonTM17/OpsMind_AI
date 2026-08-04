function requireText(contents, label, expectedTexts, errors) {
  for (const expectedText of expectedTexts) {
    if (!contents.includes(expectedText)) {
      errors.push(`${label} is missing a required contract marker`);
    }
  }
}

function operationBlock(openApi, route, method) {
  const lines = openApi.split(/\r?\n/u);
  const routeIndex = lines.findIndex((line) => line === `  ${route}:`);
  if (routeIndex === -1) return "";
  let routeEnd = lines.length;
  for (let index = routeIndex + 1; index < lines.length; index += 1) {
    if (/^  \//u.test(lines[index])) {
      routeEnd = index;
      break;
    }
  }
  const methodIndex = lines.findIndex(
    (line, index) => index > routeIndex && index < routeEnd && line === `    ${method}:`,
  );
  if (methodIndex === -1) return "";
  let endIndex = lines.length;
  for (let index = methodIndex + 1; index < lines.length; index += 1) {
    if (lines[index].trim() === "") continue;
    if (lines[index].match(/^ */u)[0].length <= 4) {
      endIndex = index;
      break;
    }
  }
  return lines.slice(methodIndex, endIndex).join("\n");
}

function hasComponent(openApi, section, name) {
  const lines = openApi.split(/\r?\n/u);
  const sectionIndex = lines.findIndex((line) => line === `  ${section}:`);
  if (sectionIndex === -1) return false;
  for (let index = sectionIndex + 1; index < lines.length; index += 1) {
    if (lines[index].trim() === "") continue;
    if (lines[index].match(/^ */u)[0].length <= 2) return false;
    if (lines[index] === `    ${name}:`) return true;
  }
  return false;
}

const routeContracts = [
  {
    route: "/organizations/{organizationId}/projects/{projectId}/incidents",
    method: "get",
    markers: [
      "operationId: listIncidents", "oidcBearer: [incident:read]",
      "#/components/parameters/OrganizationId", "#/components/parameters/ProjectId",
      "#/components/parameters/PageSize", "#/components/parameters/IncidentListPageToken",
      "name: status", "updatedAt descending", "not a snapshot or lossless change feed",
      "unsigned navigation hint", "Cache-Control:", "const: no-store",
      "'200':", "'400':", "'401':", "'403':", "'404':", "'503':",
      "#/components/schemas/IncidentListPage",
    ],
  },
  {
    route: "/organizations/{organizationId}/projects/{projectId}/incidents",
    method: "post",
    markers: [
      "operationId: createIncident", "oidcBearer: [incident:write]",
      "#/components/parameters/OrganizationId", "#/components/parameters/ProjectId",
      "#/components/parameters/IdempotencyKey", "#/components/schemas/CreateIncidentRequest",
      "'201':", "#/components/headers/Location", "#/components/headers/ETag",
      "#/components/headers/XOperationId", "'400':", "'401':", "'403':", "'404':",
      "'409':", "'413':", "'415':", "'422':", "'503':", "#/components/schemas/Incident",
    ],
  },
  {
    route: "/organizations/{organizationId}/projects/{projectId}/incidents/{incidentId}",
    method: "patch",
    markers: [
      "operationId: patchIncident", "oidcBearer: [incident:write]",
      "#/components/parameters/OrganizationId", "#/components/parameters/ProjectId",
      "#/components/parameters/IncidentId", "#/components/parameters/IdempotencyKey",
      "#/components/parameters/IfMatch", "application/merge-patch+json",
      "#/components/schemas/PatchIncidentRequest", "'200':", "#/components/headers/ETag",
      "#/components/headers/XOperationId", "'400':", "'401':", "'403':", "'404':",
      "'409':", "'412':", "'413':", "'415':", "'422':", "'428':", "'503':",
      "#/components/schemas/Incident",
    ],
  },
  {
    route: "/organizations/{organizationId}/projects/{projectId}/incidents/{incidentId}",
    method: "get",
    markers: [
      "operationId: getIncident", "oidcBearer: [incident:read]",
      "#/components/parameters/OrganizationId", "#/components/parameters/ProjectId",
      "#/components/parameters/IncidentId", "'200':", "#/components/headers/ReadModelETag",
      "'400':", "'401':", "'403':", "'404':", "'503':", "#/components/schemas/Incident",
    ],
  },
  {
    route: "/organizations/{organizationId}/projects/{projectId}/incidents/{incidentId}/transitions",
    method: "post",
    markers: [
      "operationId: transitionIncident", "oidcBearer: [incident:write]",
      "#/components/parameters/OrganizationId", "#/components/parameters/ProjectId",
      "#/components/parameters/IncidentId", "#/components/parameters/IdempotencyKey",
      "#/components/parameters/IfMatch", "#/components/schemas/TransitionIncidentRequest",
      "'200':", "#/components/headers/ETag", "#/components/headers/XOperationId",
      "'400':", "'401':", "'403':", "'404':", "'409':", "'412':", "'413':", "'415':", "'428':",
      "'422':", "'503':",
      "#/components/schemas/Incident",
    ],
  },
  {
    route: "/organizations/{organizationId}/projects/{projectId}/incidents/{incidentId}/timeline",
    method: "get",
    markers: [
      "operationId: getIncidentTimeline", "oidcBearer: [incident:read]",
      "#/components/parameters/OrganizationId", "#/components/parameters/ProjectId",
      "#/components/parameters/IncidentId", "#/components/parameters/PageSize",
      "#/components/parameters/PageToken", "'200':", "'400':", "'401':", "'403':", "'404':",
      "'406':", "'503':", "incident:analyze", "ANALYZE",
      "x-opsmind-representation-security", "requiredScope", "incidentAccessMode",
      "Vary:", "const: Accept", "Cache-Control:", "const: no-store",
      "#/components/schemas/IncidentTimelinePage",
      "application/vnd.opsmind.incident-activity-timeline.v1+json",
      "#/components/schemas/IncidentActivityTimelinePage",
    ],
  },
];

export function validateOpenApi({ openApi, openApiPath, errors, resolveLocalReference }) {
  requireText(openApi, "OpenAPI document", [
    "openapi: 3.1.1", "version: 0.4.0", "  - url: /api/v1", "type: openIdConnect",
    "incident:read", "incident:write", "X-Operation-Id:",
    "../json-schema/incidents/create-incident-request.schema.json",
    "../json-schema/incidents/patch-incident-request.schema.json",
    "../json-schema/incidents/transition-incident-request.schema.json",
    "../json-schema/incidents/incident.schema.json",
    "../json-schema/incidents/incident-summary.schema.json",
    "../json-schema/incidents/incident-list-page.schema.json",
    "../json-schema/incidents/incident-timeline-page.schema.json",
    "../json-schema/incidents/incident-activity-timeline-page.schema.json",
  ], errors);

  const operationIds = [...openApi.matchAll(
    /^\s+operationId:\s*([A-Za-z][A-Za-z0-9]*)\s*$/gmu,
  )].map((match) => match[1]);
  if (operationIds.length === 0 || new Set(operationIds).size !== operationIds.length) {
    errors.push("OpenAPI operationIds are missing or duplicated");
  }
  for (const contract of routeContracts) {
    const block = operationBlock(openApi, contract.route, contract.method);
    if (!block) {
      errors.push(`OpenAPI incident operation is missing: ${contract.method} ${contract.route}`);
    } else {
      requireText(block, `${contract.method} ${contract.route}`, contract.markers, errors);
    }
  }
  const timelineBlock = operationBlock(
    openApi,
    "/organizations/{organizationId}/projects/{projectId}/incidents/{incidentId}/timeline",
    "get",
  );
  requireText(timelineBlock, "incident activity timeline representation", [
    "security:\n        - oidcBearer: [incident:read]\n        - oidcBearer: [incident:analyze]",
    "application/json:\n          requiredScope: incident:read\n          incidentAccessMode: READ",
    "application/vnd.opsmind.incident-activity-timeline.v1+json:\n"
      + "          requiredScope: incident:analyze\n          incidentAccessMode: ANALYZE",
    "application/vnd.opsmind.incident-activity-timeline.v1+json:\n"
      + "              schema:\n"
      + "                $ref: '#/components/schemas/IncidentActivityTimelinePage'",
  ], errors);
  if ((timelineBlock.match(/IncidentActivityTimelinePage/gu) ?? []).length !== 1) {
    errors.push("incident activity timeline representation must have one exact schema binding");
  }
  if (/^\s{4}delete:/gmu.test(openApi)) {
    errors.push("OpenAPI exposes an out-of-scope delete operation");
  }
  if ((openApi.match(/^\s{4}patch:/gmu) ?? []).length !== 1) {
    errors.push("OpenAPI must expose exactly one nested incident patch operation");
  }
  if (/^\s{2}\/incidents(?:\/|:)/gmu.test(openApi)) {
    errors.push("OpenAPI exposes an unsafe flat incident route");
  }

  let referenceCount = 0;
  for (const match of openApi.matchAll(/\$ref:\s*['"]?([^'"\s]+)['"]?/gmu)) {
    const reference = match[1];
    if (reference.startsWith("#")) {
      const component = reference.match(
        /^#\/components\/([A-Za-z][A-Za-z0-9]*)\/([A-Za-z][A-Za-z0-9]*)$/u,
      );
      if (!component || !hasComponent(openApi, component[1], component[2])) {
        errors.push("OpenAPI contains an unresolved internal component reference");
      } else {
        referenceCount += 1;
      }
      continue;
    }
    try {
      resolveLocalReference(openApiPath, reference);
      referenceCount += 1;
    } catch {
      errors.push("OpenAPI contains an invalid or remote file reference");
    }
  }
  return { operationCount: operationIds.length, referenceCount };
}
