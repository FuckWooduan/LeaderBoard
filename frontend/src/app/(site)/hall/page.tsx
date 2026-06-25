import { Suspense } from "react";
import { HallView } from "@/components/HallView";

export const metadata = {
  title: "频道大厅 · 各区频道在线人数与房间列表（实时） | 生死狙击 example.com",
  description: "生死狙击各游戏大区频道在线人数、房间列表实时查看，含双线/电信/联通全区。",
};
export const dynamic = "force-dynamic";

export default function Page() {
  // HallView 用 useSearchParams（CSR-bailout），需 Suspense 边界。
  return (
    <Suspense fallback={null}>
      <HallView />
    </Suspense>
  );
}
