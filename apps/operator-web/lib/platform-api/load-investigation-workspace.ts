import { randomUUID } from "node:crypto";

import { ContractValidationError } from "@/features/investigation/contract-validation";
import {
  type InvestigationRouteIdentity,
  type InvestigationWorkspaceResult,
  type WorkspaceUnavailableReason,
} from "@/features/investigation/investigation-types";
import { parseIncident } from "@/features/investigation/parse-incident";
import { parseInvestigation } from "@/features/investigation/parse-investigation";

import {
  fetchPlatformJson,
  PlatformRequestError,
} from "./bounded-platform-fetch";
import {
  getOperatorSessionCredential,
  type OperatorSessionCredential,
} from "./operator-session";
import {
  loadPlatformClientConfiguration,
  type PlatformClientConfiguration,
} from "./platform-client-configuration";

const ROUTE_UUID =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu;

export async function loadInvestigationWorkspace(
  identity: InvestigationRouteIdentity,
): Promise<InvestigationWorkspaceResult> {
  if (!Object.values(identity).every((value) => ROUTE_UUID.test(value))) {
    return unavailable("not-found");
  }
  let session: OperatorSessionCredential;
  let configuration: PlatformClientConfiguration;
  try {
    const credential = getOperatorSessionCredential();
    if (credential === null) return unavailable("session-unavailable");
    session = credential;
    configuration = loadPlatformClientConfiguration();
  } catch {
    return unavailable("configuration-error");
  }
  const correlationId = randomUUID();
  try {
    const basePath = `/api/v1/organizations/${identity.organizationId}` +
      `/projects/${identity.projectId}/incidents/${identity.incidentId}`;
    const requestGroup = new AbortController();
    let incidentResponse: Awaited<ReturnType<typeof fetchPlatformJson>>;
    let investigationResponse: Awaited<ReturnType<typeof fetchPlatformJson>>;
    try {
      [incidentResponse, investigationResponse] = await Promise.all([
        fetchPlatformJson(
          configuration,
          basePath,
          session.accessToken,
          correlationId,
          requestGroup.signal,
        ),
        fetchPlatformJson(
          configuration,
          `${basePath}/investigations/${identity.runId}`,
          session.accessToken,
          correlationId,
          requestGroup.signal,
        ),
      ]);
    } catch (error) {
      requestGroup.abort();
      throw error;
    } finally {
      requestGroup.abort();
    }
    const incident = parseIncident(incidentResponse.body);
    const investigation = parseInvestigation(investigationResponse.body);
    verifyIdentity(identity, incident, investigation);
    return {
      kind: "ready",
      data: {
        incident,
        investigation,
        refreshedAt: new Date().toISOString(),
        correlationId,
        projectionSafety: {
          classification: incidentResponse.assurance.classification,
          redactionVersion: incidentResponse.assurance.redactionVersion,
          redactionCount:
            incidentResponse.assurance.redactionCount +
            investigationResponse.assurance.redactionCount,
          projectionCount: 2,
        },
      },
    };
  } catch (error) {
    if (error instanceof PlatformRequestError) {
      return unavailable(error.kind, error.correlationId);
    }
    if (error instanceof ContractValidationError || error instanceof TypeError) {
      return unavailable("invalid-response", correlationId);
    }
    return unavailable("internal-error", correlationId);
  }
}

function verifyIdentity(
  route: InvestigationRouteIdentity,
  incident: ReturnType<typeof parseIncident>,
  investigation: ReturnType<typeof parseInvestigation>,
): void {
  if (
    incident.id !== route.incidentId ||
    incident.organizationId !== route.organizationId ||
    incident.projectId !== route.projectId ||
    investigation.runId !== route.runId ||
    investigation.incidentId !== route.incidentId ||
    investigation.organizationId !== route.organizationId ||
    investigation.projectId !== route.projectId
  ) {
    throw new TypeError("Platform projections do not match the authorized route.");
  }
}

function unavailable(
  reason: WorkspaceUnavailableReason,
  correlationId?: string,
): InvestigationWorkspaceResult {
  const copy = {
    "session-unavailable": [
      "Secure operator session unavailable",
      "The production BFF session boundary is not configured. No browser credential fallback was attempted.",
    ],
    "not-found": [
      "Investigation not found",
      "The run is unavailable in this authorized organization, project, and incident scope.",
    ],
    "access-denied": [
      "Access denied",
      "The verified operator session cannot read this investigation scope.",
    ],
    "dependency-unavailable": [
      "Platform data unavailable",
      "The last durable state remains unchanged. No retry or downstream action was attempted.",
    ],
    "invalid-response": [
      "Projection verification failed",
      "The Platform response did not satisfy the bounded investigation contract and was not rendered.",
    ],
    "configuration-error": [
      "Operator data path unavailable",
      "The server-side Platform client is not configured safely.",
    ],
    "internal-error": [
      "Operator read failed safely",
      "The server could not complete the bounded read. Durable state remains unchanged.",
    ],
  } satisfies Record<WorkspaceUnavailableReason, [string, string]>;
  return {
    kind: "unavailable",
    reason,
    title: copy[reason][0],
    detail: copy[reason][1],
    correlationId,
  };
}
