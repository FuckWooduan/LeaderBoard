import { serverApi } from "@/lib/api";
import { BoardView, type BoardData, type ServerOption } from "@/components/BoardView";

/** 首页 = 战力榜。SSR：服务端预取区服与第一页数据（实时数据，禁止静态化）。 */
export const dynamic = "force-dynamic";

export default async function PowerPage({
  searchParams,
}: {
  searchParams: Promise<{ server?: string }>;
}) {
  const { server = "" } = await searchParams;
  const [servers, data] = await Promise.all([
    serverApi<ServerOption[]>("/api/public/servers?tab=power"),
    serverApi<BoardData>(`/api/public/tab?tab=power&server=${encodeURIComponent(server)}&page=1`),
  ]);
  return (
    <>
      <h1
        style={{
          position: "absolute",
          width: 1,
          height: 1,
          padding: 0,
          margin: -1,
          overflow: "hidden",
          clip: "rect(0 0 0 0)",
          whiteSpace: "nowrap",
          border: 0,
        }}
      >
        生死狙击排行榜 —— 战力榜、经验榜、天梯榜、战队榜、分区榜，玩家查询与武器库 · example.com
      </h1>
      <BoardView tab="power" initialServers={servers ?? undefined} initialData={data ?? undefined} />
    </>
  );
}
