import { serverApi } from "@/lib/api";
import { BoardView, type BoardData } from "@/components/BoardView";

export const metadata = { title: "天梯排行榜 | 生死狙击 example.com" };
export const dynamic = "force-dynamic";

export default async function LadderPage() {
  const data = await serverApi<BoardData>("/api/public/tab?tab=ladder&server=&page=1");
  return <BoardView tab="ladder" initialData={data ?? undefined} />;
}
