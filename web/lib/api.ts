export const API_BASE =
  process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

export type Wallet = {
  id: string;
  owner: string;
  currency: string;
  balance: number;
  createdAt: string;
};

export type Payment = {
  id: string;
  walletId: string;
  amount: number;
  currency: string;
  status: "PENDING" | "COMPLETED" | "FAILED";
  providerRef: string | null;
  createdAt: string;
};

export type Transaction = {
  id: string;
  paymentId: string;
  direction: "DEBIT" | "CREDIT";
  amount: number;
  createdAt: string;
};

export async function getWallets(): Promise<Wallet[]> {
  const res = await fetch(`${API_BASE}/api/v1/wallets`, { cache: "no-store" });
  if (!res.ok) throw new Error(`wallets: HTTP ${res.status}`);
  return res.json();
}

export async function getTransactions(walletId: string): Promise<Transaction[]> {
  const res = await fetch(`${API_BASE}/api/v1/wallets/${walletId}/transactions`, {
    cache: "no-store",
  });
  if (!res.ok) throw new Error(`transactions: HTTP ${res.status}`);
  return res.json();
}

export async function getPayment(id: string): Promise<Payment> {
  const res = await fetch(`${API_BASE}/api/v1/payments/${id}`, { cache: "no-store" });
  if (!res.ok) throw new Error(`payment: HTTP ${res.status}`);
  return res.json();
}

export type CreateResult = {
  status: number;
  replayed: boolean;
  body: Payment | { title?: string; detail?: string };
};

export async function createPayment(
  idempotencyKey: string,
  payload: { walletId: string; amount: number; currency: string; cardRef?: string },
): Promise<CreateResult> {
  const res = await fetch(`${API_BASE}/api/v1/payments`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Idempotency-Key": idempotencyKey,
    },
    body: JSON.stringify(payload),
  });
  return {
    status: res.status,
    replayed: res.headers.get("Idempotency-Replayed") === "true",
    body: await res.json(),
  };
}

export async function resetDemo(password: string): Promise<Wallet[]> {
  const login = await fetch(`${API_BASE}/api/v1/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username: "admin", password }),
  });
  if (!login.ok) throw new Error("Login failed — wrong admin password?");
  const { accessToken } = await login.json();
  const res = await fetch(`${API_BASE}/api/v1/demo/reset`, {
    method: "POST",
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  if (!res.ok) throw new Error(`Reset failed: HTTP ${res.status}`);
  return res.json();
}
