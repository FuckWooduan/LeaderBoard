import { serverApi } from "@/lib/api";
import { SliceView, type SliceGrid, type SliceKey } from "@/components/SliceView";

export const metadata = { title: "分区排行榜 · 200 分区热力图与龙虎榜 | 生死狙击 example.com" };
export const dynamic = "force-dynamic";

/** SSR 预取 keys + 热力图 grid，首屏即着色（否则 200 个格子会先闪一帧红色再变色）。 */
export default async function Page({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const sp = await searchParams;
  const urlKey = typeof sp.key === "string" ? sp.key : "";

  const all = (await serverApi<SliceKey[]>("/api/public/slice/keys")) || [];
  const vis = all.filter((k) => k.visible !== false);
  const key = urlKey && vis.some((k) => k.rankKey === urlKey) ? urlKey : vis[0]?.rankKey || "";
  const grid = key
    ? await serverApi<{ coverage?: number[]; maxSource?: number }>(
        `/api/public/slice/grid?rankKey=${encodeURIComponent(key)}`
      )
    : null;
  const initialGrid: SliceGrid | null = grid
    ? { coverage: grid.coverage || [], maxSource: grid.maxSource || 0 }
    : null;

  return <SliceView initialKeys={vis} initialKey={key} initialGrid={initialGrid} />;
}
