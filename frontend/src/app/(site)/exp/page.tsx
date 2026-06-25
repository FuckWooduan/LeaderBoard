import { serverApi } from "@/lib/api";
import { BoardView, type BoardData, type ServerOption } from "@/components/BoardView";

export const metadata = { title: "经验排行榜 | 生死狙击 example.com" };
export const dynamic = "force-dynamic";

export default async function ExpPage({
  searchParams,
}: {
  searchParams: Promise<{ server?: string }>;
}) {
  const { server = "" } = await searchParams;
  const [servers, data] = await Promise.all([
    serverApi<ServerOption[]>("/api/public/servers?tab=exp"),
    serverApi<BoardData>(`/api/public/tab?tab=exp&server=${encodeURIComponent(server)}&page=1`),
  ]);
  return <BoardView tab="exp" initialServers={servers ?? undefined} initialData={data ?? undefined} />;
}
