import { UnavailableWorkspace } from "@/features/investigation/unavailable-workspace";

export const dynamic = "force-dynamic";

export default function OperatorWorkspaceEntryPage() {
  return (
    <UnavailableWorkspace
      unavailable={{
        kind: "unavailable",
        reason: "session-unavailable",
        title: "Select an authorized investigation",
        detail:
          "Open an organization, project, incident, and investigation route through the server-owned operator session.",
      }}
    />
  );
}
