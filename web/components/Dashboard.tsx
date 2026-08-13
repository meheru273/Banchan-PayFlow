"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import {
  API_BASE,
  createPayment,
  getPayment,
  getTransactions,
  getWallets,
  resetDemo,
  type CreateResult,
  type Transaction,
  type Wallet,
} from "@/lib/api";

type EventKind = "create" | "completed" | "replay" | "conflict" | "reset" | "error" | "info";

type LogEvent = { id: number; time: string; kind: EventKind; text: string };

const KIND_STYLES: Record<EventKind, string> = {
  create: "text-sky-300",
  completed: "text-emerald-300",
  replay: "text-amber-300",
  conflict: "text-orange-400",
  reset: "text-fuchsia-300",
  error: "text-red-400",
  info: "text-zinc-400",
};

const KIND_LABELS: Record<EventKind, string> = {
  create: "payment.created",
  completed: "payment.completed",
  replay: "idempotent.replay",
  conflict: "idempotency.conflict",
  reset: "demo.reset",
  error: "error",
  info: "info",
};

let eventSeq = 0;

export default function Dashboard() {
  const [wallets, setWallets] = useState<Wallet[]>([]);
  const [selected, setSelected] = useState<string>("");
  const [transactions, setTransactions] = useState<Transaction[]>([]);
  const [events, setEvents] = useState<LogEvent[]>([]);
  const [amount, setAmount] = useState("12.50");
  const [awake, setAwake] = useState(false);
  const [busy, setBusy] = useState(false);
  const [resetOpen, setResetOpen] = useState(false);
  const [adminPassword, setAdminPassword] = useState("");
  const lastRequest = useRef<{ key: string; walletId: string; amount: number } | null>(null);
  const watching = useRef<Set<string>>(new Set());

  const log = useCallback((kind: EventKind, text: string) => {
    setEvents((prev) =>
      [{ id: eventSeq++, time: new Date().toLocaleTimeString(), kind, text }, ...prev].slice(0, 60),
    );
  }, []);

  const refresh = useCallback(async () => {
    try {
      const ws = await getWallets();
      setWallets(ws);
      setAwake(true);
      setSelected((cur) => cur || ws[0]?.id || "");
    } catch {
      /* backend waking up — the banner explains it */
    }
  }, []);

  useEffect(() => {
    refresh();
    const t = setInterval(refresh, 4000);
    return () => clearInterval(t);
  }, [refresh]);

  useEffect(() => {
    if (!selected) return;
    let cancelled = false;
    const load = async () => {
      try {
        const tx = await getTransactions(selected);
        if (!cancelled) setTransactions(tx);
      } catch {
        /* transient */
      }
    };
    load();
    const t = setInterval(load, 4000);
    return () => {
      cancelled = true;
      clearInterval(t);
    };
  }, [selected]);

  const watchUntilCompleted = useCallback(
    (paymentId: string) => {
      if (watching.current.has(paymentId)) return;
      watching.current.add(paymentId);
      const started = Date.now();
      const poll = async () => {
        try {
          const p = await getPayment(paymentId);
          if (p.status === "COMPLETED") {
            log(
              "completed",
              `ledger posted for ${paymentId.slice(0, 8)}… (+${p.amount.toFixed(2)} ${p.currency}) — via provider webhook → RabbitMQ → ledger-worker`,
            );
            watching.current.delete(paymentId);
            refresh();
            return;
          }
          if (p.status === "FAILED") {
            log("error", `payment ${paymentId.slice(0, 8)}… FAILED`);
            watching.current.delete(paymentId);
            return;
          }
        } catch {
          /* keep polling */
        }
        if (Date.now() - started < 120_000) setTimeout(poll, 1500);
        else {
          watching.current.delete(paymentId);
          log("info", `stopped watching ${paymentId.slice(0, 8)}… (still pending — worker may be waking up)`);
        }
      };
      setTimeout(poll, 1200);
    },
    [log, refresh],
  );

  const handleResult = useCallback(
    (r: CreateResult, walletOwner: string) => {
      if (r.status === 201 && "id" in r.body) {
        const p = r.body;
        if (r.replayed) {
          log("replay", `same Idempotency-Key + same body → stored response returned, nothing re-executed (${p.id.slice(0, 8)}…)`);
        } else {
          log("create", `payment ${p.id.slice(0, 8)}… for ${walletOwner} is ${p.status} (${p.amount.toFixed(2)} ${p.currency})`);
          watchUntilCompleted(p.id);
        }
      } else if (r.status === 409) {
        const detail = "detail" in r.body ? r.body.detail : "";
        log("conflict", `409 — ${detail}`);
      } else {
        const detail = "detail" in r.body ? r.body.detail : `HTTP ${r.status}`;
        log("error", `create failed: ${detail}`);
      }
    },
    [log, watchUntilCompleted],
  );

  const pay = async () => {
    const wallet = wallets.find((w) => w.id === selected);
    const value = Number(amount);
    if (!wallet || !Number.isFinite(value) || value <= 0) {
      log("error", "pick a wallet and a positive amount");
      return;
    }
    setBusy(true);
    try {
      const key = crypto.randomUUID();
      lastRequest.current = { key, walletId: wallet.id, amount: value };
      const r = await createPayment(key, {
        walletId: wallet.id,
        amount: value,
        currency: wallet.currency,
        cardRef: "tok_visa_4242",
      });
      handleResult(r, wallet.owner);
    } catch (e) {
      log("error", String(e));
    } finally {
      setBusy(false);
    }
  };

  const replayLast = async () => {
    const last = lastRequest.current;
    if (!last) return;
    const wallet = wallets.find((w) => w.id === last.walletId);
    setBusy(true);
    try {
      const r = await createPayment(last.key, {
        walletId: last.walletId,
        amount: last.amount,
        currency: wallet?.currency ?? "GBP",
        cardRef: "tok_visa_4242",
      });
      handleResult(r, wallet?.owner ?? "wallet");
    } catch (e) {
      log("error", String(e));
    } finally {
      setBusy(false);
    }
  };

  const doReset = async () => {
    setBusy(true);
    try {
      await resetDemo(adminPassword);
      log("reset", "demo data wiped and reseeded");
      setResetOpen(false);
      setAdminPassword("");
      watching.current.clear();
      await refresh();
    } catch (e) {
      log("error", String(e));
    } finally {
      setBusy(false);
    }
  };

  const selectedWallet = wallets.find((w) => w.id === selected);

  return (
    <main className="mx-auto max-w-5xl px-4 py-8">
      <header className="mb-6 flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">PayFlow</h1>
          <p className="text-sm text-zinc-400">
            Spring Boot payments · double-entry ledger · RabbitMQ — watch a payment travel the queue
          </p>
        </div>
        <nav className="flex gap-4 text-sm">
          <a className="text-sky-400 hover:underline" href={`${API_BASE}/swagger-ui.html`} target="_blank">
            Swagger
          </a>
          <a
            className="text-sky-400 hover:underline"
            href="https://github.com/meheru273/Banchan-PayFlow"
            target="_blank"
          >
            GitHub
          </a>
        </nav>
      </header>

      {!awake && (
        <div className="mb-6 rounded-lg border border-amber-500/40 bg-amber-500/10 px-4 py-3 text-sm text-amber-200">
          Waking the backend… it runs on a free tier that sleeps when idle, so the first load can
          take <strong>30–50 seconds</strong>. That&apos;s the hosting plan, not the app.
        </div>
      )}

      <section className="mb-6 grid gap-3 sm:grid-cols-2">
        {wallets.map((w) => (
          <button
            key={w.id}
            onClick={() => setSelected(w.id)}
            className={`rounded-xl border p-4 text-left transition ${
              w.id === selected
                ? "border-sky-500 bg-sky-500/10"
                : "border-zinc-800 bg-zinc-900 hover:border-zinc-600"
            }`}
          >
            <div className="text-sm text-zinc-400">{w.owner}</div>
            <div className="mt-1 text-2xl font-semibold tabular-nums">
              {w.currency} {w.balance.toFixed(2)}
            </div>
          </button>
        ))}
        {wallets.length === 0 && (
          <div className="rounded-xl border border-zinc-800 bg-zinc-900 p-4 text-sm text-zinc-500 sm:col-span-2">
            Loading wallets…
          </div>
        )}
      </section>

      <section className="mb-6 rounded-xl border border-zinc-800 bg-zinc-900 p-4">
        <h2 className="mb-3 text-sm font-medium uppercase tracking-wide text-zinc-400">
          Make a payment {selectedWallet ? `→ ${selectedWallet.owner}` : ""}
        </h2>
        <div className="flex flex-wrap items-center gap-3">
          <input
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            inputMode="decimal"
            className="w-32 rounded-lg border border-zinc-700 bg-zinc-950 px-3 py-2 tabular-nums outline-none focus:border-sky-500"
            aria-label="Amount"
          />
          <button
            onClick={pay}
            disabled={busy || !selectedWallet}
            className="rounded-lg bg-sky-600 px-4 py-2 font-medium hover:bg-sky-500 disabled:opacity-40"
          >
            Pay
          </button>
          <button
            onClick={replayLast}
            disabled={busy || !lastRequest.current}
            title="Re-sends the exact same request with the same Idempotency-Key — the API returns the stored response instead of charging twice"
            className="rounded-lg border border-amber-500/50 px-4 py-2 text-amber-300 hover:bg-amber-500/10 disabled:opacity-40"
          >
            Replay last (idempotency demo)
          </button>
          <div className="ml-auto">
            {resetOpen ? (
              <span className="flex items-center gap-2">
                <input
                  type="password"
                  placeholder="admin password"
                  value={adminPassword}
                  onChange={(e) => setAdminPassword(e.target.value)}
                  className="w-40 rounded-lg border border-zinc-700 bg-zinc-950 px-3 py-2 text-sm outline-none focus:border-fuchsia-500"
                />
                <button
                  onClick={doReset}
                  disabled={busy || !adminPassword}
                  className="rounded-lg bg-fuchsia-600 px-3 py-2 text-sm hover:bg-fuchsia-500 disabled:opacity-40"
                >
                  Reset
                </button>
                <button
                  onClick={() => setResetOpen(false)}
                  className="text-sm text-zinc-500 hover:text-zinc-300"
                >
                  ✕
                </button>
              </span>
            ) : (
              <button
                onClick={() => setResetOpen(true)}
                className="rounded-lg border border-zinc-700 px-3 py-2 text-sm text-zinc-400 hover:border-fuchsia-500 hover:text-fuchsia-300"
              >
                Reset demo
              </button>
            )}
          </div>
        </div>
        <p className="mt-2 text-xs text-zinc-500">
          Payments start <span className="text-sky-300">PENDING</span>; a simulated provider confirms via an
          HMAC-signed webhook, the event rides RabbitMQ, and the ledger-worker posts the balanced
          DEBIT/CREDIT pair → <span className="text-emerald-300">COMPLETED</span>. No real money, ever.
        </p>
      </section>

      <div className="grid gap-6 lg:grid-cols-2">
        <section className="rounded-xl border border-zinc-800 bg-zinc-900 p-4">
          <h2 className="mb-3 text-sm font-medium uppercase tracking-wide text-zinc-400">
            Transactions {selectedWallet ? `— ${selectedWallet.owner}` : ""}
          </h2>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-zinc-800 text-left text-zinc-500">
                  <th className="py-2 pr-3 font-normal">Time</th>
                  <th className="py-2 pr-3 font-normal">Payment</th>
                  <th className="py-2 pr-3 font-normal">Direction</th>
                  <th className="py-2 text-right font-normal">Amount</th>
                </tr>
              </thead>
              <tbody>
                {transactions.map((t) => (
                  <tr key={t.id} className="border-b border-zinc-800/60">
                    <td className="py-2 pr-3 text-zinc-400">
                      {new Date(t.createdAt).toLocaleTimeString()}
                    </td>
                    <td className="py-2 pr-3 font-mono text-xs text-zinc-400">
                      {t.paymentId.slice(0, 8)}…
                    </td>
                    <td className="py-2 pr-3">
                      <span
                        className={
                          t.direction === "CREDIT" ? "text-emerald-300" : "text-orange-300"
                        }
                      >
                        {t.direction}
                      </span>
                    </td>
                    <td className="py-2 text-right tabular-nums">{t.amount.toFixed(2)}</td>
                  </tr>
                ))}
                {transactions.length === 0 && (
                  <tr>
                    <td colSpan={4} className="py-6 text-center text-zinc-600">
                      No transactions yet — make a payment.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </section>

        <section className="rounded-xl border border-zinc-800 bg-zinc-900 p-4">
          <h2 className="mb-3 text-sm font-medium uppercase tracking-wide text-zinc-400">
            Live event log
          </h2>
          <div className="max-h-96 space-y-1.5 overflow-y-auto font-mono text-xs leading-relaxed">
            {events.map((e) => (
              <div key={e.id}>
                <span className="text-zinc-600">{e.time}</span>{" "}
                <span className={KIND_STYLES[e.kind]}>[{KIND_LABELS[e.kind]}]</span>{" "}
                <span className="text-zinc-300">{e.text}</span>
              </div>
            ))}
            {events.length === 0 && (
              <div className="py-6 text-center text-zinc-600">
                Events appear here as payments move through the pipeline.
              </div>
            )}
          </div>
        </section>
      </div>

      <footer className="mt-8 text-center text-xs text-zinc-600">
        Java 21 · Spring Boot 3 · PostgreSQL (Neon) · RabbitMQ (CloudAMQP) · Render · Next.js on Vercel
      </footer>
    </main>
  );
}
