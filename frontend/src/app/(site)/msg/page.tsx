import { serverApi } from "@/lib/api";
import { MsgView, type MsgPage } from "@/components/MsgView";

export const metadata = { title: "留言板 | 生死狙击 example.com" };
export const dynamic = "force-dynamic";

export default async function Page() {
  const initial = await serverApi<MsgPage>("/api/public/messages?page=1");
  return <MsgView initial={initial ?? undefined} />;
}
