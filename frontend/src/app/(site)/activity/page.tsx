import { serverApi } from "@/lib/api";
import { ActivityView, type ActivityPage } from "@/components/ActivityView";

export const metadata = { title: "活动通知 · 长图与 SWF 在线解析 | 生死狙击 example.com" };
export const dynamic = "force-dynamic";

export default async function Page() {
  const initial = await serverApi<ActivityPage>("/api/public/activities?page=0&size=10");
  return <ActivityView initial={initial ?? undefined} />;
}
