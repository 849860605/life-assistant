/** 记账支持的币种，和后端 TripLedgerService.CURRENCIES 一致 */
export const CURRENCIES = ["CNY", "HKD", "MOP", "TWD", "USD", "EUR", "GBP", "JPY", "KRW", "THB", "SGD", "MYR", "AUD"];

const SYMBOL: Record<string, string> = { CNY: "¥", HKD: "HK$", MOP: "MOP$", TWD: "NT$", USD: "$", EUR: "€", GBP: "£", JPY: "JP¥", KRW: "₩", THB: "฿", SGD: "S$", MYR: "RM", AUD: "A$" };

/** 金额加币种符号，保留两位小数（整数不带 .00） */
export function money(n: number | string | null | undefined, currency = "CNY"): string {
  const v = Number(n || 0);
  const text = Number.isInteger(v) ? v.toLocaleString("zh-CN") : v.toLocaleString("zh-CN", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  return (SYMBOL[currency] || currency + " ") + text;
}
