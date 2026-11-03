import { reactive } from "vue";
import { api } from "../api";
import type { StayQuote } from "../api/types";

/** 一家酒店在各平台的最低价：loading 表示还在查；没查到价格时 price 为空。 */
export interface HotelPrice {
  loading: boolean;
  price?: number;
  platform?: string;
  url?: string;
  quotes?: StayQuote[];
}

// 按"酒店|城市|入住|离店"记住查过的价格，同一页面里换来换去不重复查
const prices = reactive<Record<string, HotelPrice>>({});
const queue: { key: string; name: string; city: string; checkIn: string; checkOut: string }[] = [];
let running = 0;
/** 同时最多查这么多家：每家要同时问四个平台，美团同时查太多会限流 */
const PARALLEL = 3;

function keyOf(name: string, city: string, checkIn: string, checkOut: string) {
  return `${name}|${city}|${checkIn}|${checkOut}`;
}

/** 已经查到（或正在查）的价格，没查过返回 undefined。 */
export function hotelPrice(name: string, city: string, checkIn?: string, checkOut?: string): HotelPrice | undefined {
  if (!checkIn || !checkOut) return undefined;
  return prices[keyOf(name, city, checkIn, checkOut)];
}

/** 排队查这几家酒店的各平台价格（十几秒一家，后端缓存 30 分钟），查过的跳过。 */
export function loadHotelPrices(names: string[], city: string, checkIn?: string, checkOut?: string) {
  if (!city || !checkIn || !checkOut || checkOut <= checkIn) return;
  for (const name of names) {
    const key = keyOf(name, city, checkIn, checkOut);
    if (prices[key]) continue;
    prices[key] = { loading: true };
    queue.push({ key, name, city, checkIn, checkOut });
  }
  pump();
}

function pump() {
  while (running < PARALLEL && queue.length) {
    const job = queue.shift()!;
    running++;
    api
      .hotelQuote(job.name, job.city, job.checkIn, job.checkOut)
      .then((c) => {
        const ok = (c.hotels[0]?.quotes || []).filter((q) => q.status === "OK" && q.price != null);
        const best = [...ok].sort((a, b) => a.price! - b.price!)[0];
        prices[job.key] = {
          loading: false,
          price: best?.price ?? undefined,
          platform: best?.platform,
          url: best?.url ?? undefined,
          quotes: ok.map((q) => ({ platform: q.platform, price: q.price!, url: q.url ?? undefined, basis: q.basis ?? undefined, matchedName: q.matchedName ?? undefined })),
        };
      })
      .catch(() => {
        prices[job.key] = { loading: false };
      })
      .finally(() => {
        running--;
        pump();
      });
  }
}

/** 价格的一行字："¥328/晚起 · 携程"、"查价中…"、"暂无报价"；没开始查返回空串。 */
export function priceText(p?: HotelPrice): string {
  if (!p) return "";
  if (p.loading) return "查各平台价格中…";
  return p.price != null ? `¥${p.price}/晚起 · ${p.platform}` : "各平台暂无报价";
}

/** 酒店入住、离店日期：有对应住宿用住宿的，没有就按这一天入住、住一晚。 */
export function stayDates(stay: { checkIn: string; checkOut: string } | null | undefined, date?: string): [string?, string?] {
  if (stay?.checkIn && stay?.checkOut) return [stay.checkIn, stay.checkOut];
  if (!date) return [undefined, undefined];
  const next = new Date(`${date}T00:00:00`);
  next.setDate(next.getDate() + 1);
  const pad = (n: number) => String(n).padStart(2, "0");
  return [date, `${next.getFullYear()}-${pad(next.getMonth() + 1)}-${pad(next.getDate())}`];
}
