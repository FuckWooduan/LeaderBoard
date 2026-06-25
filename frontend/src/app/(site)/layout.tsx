import { SiteShell } from "@/components/SiteShell";
import { Suspense } from "react";

export default function SiteLayout({ children }: { children: React.ReactNode }) {
  return (
    <SiteShell>
      <Suspense>{children}</Suspense>
    </SiteShell>
  );
}
