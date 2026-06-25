import { serverApi } from "@/lib/api";
import { BoardView, type BoardData, type ServerOption } from "@/components/BoardView";

export const metadata = { title: "战队排行榜 | 生死狙击 example.com" };
export const dynamic = "force-dynamic";

export default async function TeamPage({
  searchParams,
}: {
  searchParams: Promise<{ server?: string }>;
}) {
  const { server = "" } = await searchParams;
  const [servers, data] = await Promise.all([
    serverApi<ServerOption[]>("/api/public/servers?tab=team"),
    serverApi<BoardData>(`/api/public/tab?tab=team&server=${encodeURIComponent(server)}&page=1&sort=normal`),
  ]);
  return <BoardView tab="team" initialServers={servers ?? undefined} initialData={data ?? undefined} />;
}
