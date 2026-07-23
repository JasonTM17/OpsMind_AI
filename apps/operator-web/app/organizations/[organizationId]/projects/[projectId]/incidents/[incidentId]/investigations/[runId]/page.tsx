import { InvestigationWorkspace } from "@/features/investigation/investigation-workspace";
import type { InvestigationRouteIdentity } from "@/features/investigation/investigation-types";
import { UnavailableWorkspace } from "@/features/investigation/unavailable-workspace";
import { loadInvestigationWorkspace } from "@/lib/platform-api/load-investigation-workspace";

export const dynamic = "force-dynamic";

interface InvestigationPageProps {
  params: Promise<InvestigationRouteIdentity>;
}

export default async function InvestigationPage({ params }: InvestigationPageProps) {
  const result = await loadInvestigationWorkspace(await params);
  if (result.kind === "unavailable") {
    return <UnavailableWorkspace unavailable={result} />;
  }
  return <InvestigationWorkspace data={result.data} />;
}
