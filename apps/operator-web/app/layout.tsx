import type { Metadata } from "next";
import type { ReactNode } from "react";
import "./styles.css";

export const metadata: Metadata = {
  title: {
    default: "OpsMind AI · Operator workspace",
    template: "%s · OpsMind AI",
  },
  description: "Evidence-first incident investigation for authorized operators.",
  applicationName: "OpsMind AI",
  robots: {
    index: false,
    follow: false,
  },
};

export default function RootLayout({ children }: Readonly<{ children: ReactNode }>) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
