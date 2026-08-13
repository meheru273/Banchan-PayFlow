import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "PayFlow Dashboard",
  description:
    "Payment + double-entry wallet service — Spring Boot, RabbitMQ, PostgreSQL. Watch a payment travel through the queue.",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
