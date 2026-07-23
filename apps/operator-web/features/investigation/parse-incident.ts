import {
  enumeration,
  integer,
  nullableText,
  record,
  text,
  timestamp,
  uuid,
} from "./contract-validation";
import {
  INCIDENT_SEVERITIES,
  INCIDENT_STATUSES,
  type IncidentView,
} from "./investigation-types";

const INCIDENT_KEYS = [
  "id",
  "organizationId",
  "projectId",
  "title",
  "summary",
  "severity",
  "status",
  "rootCause",
  "resolutionSummary",
  "createdBy",
  "updatedBy",
  "createdAt",
  "updatedAt",
  "version",
] as const;

export function parseIncident(value: unknown): IncidentView {
  const source = record(value, "incident", INCIDENT_KEYS, INCIDENT_KEYS);
  const result: IncidentView = {
    id: uuid(source.id, "incident.id"),
    organizationId: uuid(source.organizationId, "incident.organizationId"),
    projectId: uuid(source.projectId, "incident.projectId"),
    title: text(source.title, "incident.title", 1, 160),
    summary: text(source.summary, "incident.summary", 1, 4_000),
    severity: enumeration(source.severity, "incident.severity", INCIDENT_SEVERITIES),
    status: enumeration(source.status, "incident.status", INCIDENT_STATUSES),
    rootCause: nullableText(source.rootCause, "incident.rootCause", 8_000),
    resolutionSummary: nullableText(
      source.resolutionSummary,
      "incident.resolutionSummary",
      8_000,
    ),
    createdBy: uuid(source.createdBy, "incident.createdBy"),
    updatedBy: uuid(source.updatedBy, "incident.updatedBy"),
    createdAt: timestamp(source.createdAt, "incident.createdAt"),
    updatedAt: timestamp(source.updatedAt, "incident.updatedAt"),
    version: integer(source.version, "incident.version", 0, 2_147_483_647),
  };
  if (
    ["RESOLVED", "CLOSED"].includes(result.status) &&
    (result.rootCause === null || result.resolutionSummary === null)
  ) {
    throw new TypeError("resolved incident requires root cause and resolution summary");
  }
  return result;
}
