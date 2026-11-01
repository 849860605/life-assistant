import type { Flight, ItineraryDay } from "../api/types";

/** 起飞时段 */
export const SLOTS = [
  { key: "early", label: "早上 9 点前", from: 0, to: 9 },
  { key: "am", label: "上午 9–12 点", from: 9, to: 12 },
  { key: "pm", label: "下午 12–18 点", from: 12, to: 18 },
  { key: "night", label: "晚上 18 点后", from: 18, to: 24 },
] as const;

export type FlightSort = "recommend" | "price" | "dep" | "duration";

export interface FlightFilter {
  /** all 全部、direct 只看直飞、transfer 只看中转 */
  stops: "all" | "direct" | "transfer";
  slots: string[];
  airlines: string[];
  depAirports: string[];
  maxPrice: number | null;
  /** 全程不超过几小时，空表示不限 */
  maxHours: number | null;
}

export function toMin(hhmm: string) {
  const [h, m] = hhmm.split(":").map(Number);
  return h * 60 + m;
}

/** 全程分钟：平台给了用平台的，没给就按起降时间算（国内同一时区；跨天加一天） */
export function minutesOf(f: Flight): number | null {
  if (f.minutes) return f.minutes;
  if (!f.depTime || !f.arrTime) return null;
  const d = toMin(f.arrTime) - toMin(f.depTime);
  return d + (f.nextDay || d < 0 ? 1440 : 0);
}

export function durationText(min: number | null) {
  if (min == null) return "—";
  const h = Math.floor(min / 60);
  return h ? `${h}小时${min % 60 ? (min % 60) + "分" : ""}` : `${min}分钟`;
}

/** 机场写法统一一下："上海·浦东"、"浦东T2" 都归到 "浦东" */
export function airportName(text: string | null) {
  return (text || "").replace(/^.*·/, "").replace(/T\d$/i, "").replace(/(国际)?机场$/, "").trim();
}

export function applyFilter(list: Flight[], f: FlightFilter, sort: FlightSort): Flight[] {
  const out = list.filter((x) => {
    if (f.stops === "direct" && x.transfer) return false;
    if (f.stops === "transfer" && !x.transfer) return false;
    if (f.slots.length) {
      const h = Math.floor(toMin(x.depTime) / 60);
      if (!SLOTS.some((s) => f.slots.includes(s.key) && h >= s.from && h < s.to)) return false;
    }
    if (f.airlines.length && !f.airlines.includes(x.airline)) return false;
    if (f.depAirports.length && !f.depAirports.includes(airportName(x.depAirport))) return false;
    if (f.maxPrice != null && (x.lowest == null || x.lowest > f.maxPrice)) return false;
    if (f.maxHours != null && (minutesOf(x) ?? 0) > f.maxHours * 60) return false;
    return true;
  });
  const byPrice = (a: Flight, b: Flight) => (a.lowest ?? 1e9) - (b.lowest ?? 1e9);
  if (sort === "dep") out.sort((a, b) => toMin(a.depTime) - toMin(b.depTime) || byPrice(a, b));
  else if (sort === "duration") out.sort((a, b) => (minutesOf(a) ?? 1e9) - (minutesOf(b) ?? 1e9) || byPrice(a, b));
  else if (sort === "recommend") {
    // 推荐：价格为主，直飞、时长短、起飞时间不太早不太晚的往前（每项折成钱）
    const score = (x: Flight) => {
      const h = toMin(x.depTime) / 60;
      return (x.lowest ?? 1e5) + (x.transfer ? 300 : 0) + (minutesOf(x) ?? 300) * 0.8 + (h < 7 || h >= 22 ? 120 : h < 8 ? 50 : 0) + (x.nextDay ? 200 : 0);
    };
    out.sort((a, b) => score(a) - score(b));
  } else out.sort(byPrice);
  return out;
}

/**
 * 和行程的衔接：去程看落地后离当天第一项安排（交通除外）还有多久，回程看最后一项安排结束后离起飞还有多久。
 * 门槛按经验：落地到市区一般 1 小时以上，国内航班一般提前 2 小时到机场。
 */
export interface TripFit {
  tone: "good" | "tight" | "bad";
  text: string;
}

export function tripFit(f: Flight, day: ItineraryDay | undefined, leg: "out" | "back"): TripFit | null {
  if (!day) return null;
  const items = day.items.filter((i) => i.type !== "TRANSPORT" && i.start);
  if (leg === "out") {
    const first = items[0];
    if (!first?.start) return null;
    if (f.nextDay) return { tone: "bad", text: "次日才到，赶不上当天的安排" };
    const gap = toMin(first.start) - toMin(f.arrTime);
    const name = short(first.name);
    if (gap < 60) return { tone: "bad", text: gap < 0 ? `${f.arrTime} 落地时「${name}」（${first.start}）已经开始了` : `落地后只有 ${gap} 分钟就到「${name}」（${first.start}），来不及` };
    if (gap < 90) return { tone: "tight", text: `落地后 ${gap} 分钟到「${name}」（${first.start}），有点赶` };
    return { tone: "good", text: `${f.arrTime} 落地，离「${name}」（${first.start}）还有 ${durationText(gap)}` };
  }
  const last = [...items].reverse().find((i) => i.end || i.start);
  if (!last) return null;
  const end = last.end || last.start!;
  const gap = toMin(f.depTime) - toMin(end);
  const name = short(last.name);
  if (gap < 120) return { tone: "bad", text: gap < 0 ? `起飞时「${name}」（到 ${end}）还没结束` : `「${name}」${end} 结束，离起飞只有 ${gap} 分钟，不够到机场` };
  if (gap < 150) return { tone: "tight", text: `「${name}」${end} 结束后 ${gap} 分钟起飞，有点赶` };
  return { tone: "good", text: `「${name}」${end} 结束，离起飞还有 ${durationText(gap)}` };
}

function short(name: string) {
  return name.replace(/^(早餐|午餐|晚餐)[：:]\s*/, "").replace(/[（(].*?[）)]/g, "").slice(0, 14);
}

export interface Pick {
  key: string;
  title: string;
  flight: Flight;
  reasons: string[];
}

/**
 * 推荐：最便宜、最省时间、时间最合适（有行程时按行程衔接）各挑一班，同一班合并标题。
 * 理由全部按价格、时间、时长算出来，不让模型写。
 */
export function recommend(list: Flight[], day: ItineraryDay | undefined, leg: "out" | "back"): Pick[] {
  const priced = list.filter((f) => f.lowest != null);
  if (!priced.length) return [];
  const cheapest = [...priced].sort((a, b) => a.lowest! - b.lowest!)[0];
  const direct = priced.filter((f) => !f.transfer);
  const fastest = [...direct].sort((a, b) => (minutesOf(a) ?? 1e9) - (minutesOf(b) ?? 1e9) || a.lowest! - b.lowest!)[0];
  // 时间合适：直飞、不太早不太晚；有行程时要衔接得上
  const comfy = direct
    .filter((f) => {
      const fit = tripFit(f, day, leg);
      if (fit) return fit.tone === "good";
      const h = toMin(f.depTime) / 60;
      return h >= 8 && h < 20 && !f.nextDay;
    })
    .sort((a, b) => a.lowest! - b.lowest!)[0];

  const picks: Pick[] = [];
  const add = (key: string, title: string, f: Flight | undefined, why: string[]) => {
    if (!f) return;
    const same = picks.find((p) => p.flight.flightNo === f.flightNo);
    if (same) {
      same.title += " · " + title;
      same.reasons.push(...why.filter((w) => !same.reasons.includes(w)));
    } else picks.push({ key, title, flight: f, reasons: why });
  };
  add("cheap", "最便宜", cheapest, [`查到的 ${priced.length} 班里最便宜：¥${cheapest.lowest}（${cheapest.lowestPlatform}）`]);
  if (fastest) add("fast", "最省时间", fastest, [`直飞，全程 ${durationText(minutesOf(fastest))}`, ...diff(fastest, cheapest)]);
  if (comfy) {
    const fit = tripFit(comfy, day, leg);
    add("fit", day ? "最适合行程" : "时间最舒服", comfy, [fit ? fit.text : `${comfy.depTime} 起飞，不用早起也不会太晚`, ...diff(comfy, cheapest)]);
  }
  for (const p of picks) p.reasons.push(...warnings(p.flight, day, leg));
  return picks;
}

/** 和最便宜那班比贵多少 */
function diff(f: Flight, cheapest: Flight): string[] {
  if (f.flightNo === cheapest.flightNo || f.lowest == null || cheapest.lowest == null) return [];
  return [`比最便宜的 ${cheapest.flightNo} 贵 ¥${f.lowest - cheapest.lowest}`];
}

/** 需要提醒的地方：太早起飞、中转、跨天、和行程衔接不上 */
export function warnings(f: Flight, day: ItineraryDay | undefined, leg: "out" | "back"): string[] {
  const out: string[] = [];
  if (toMin(f.depTime) < 7 * 60 + 30) out.push(`${f.depTime} 起飞，要很早出门`);
  if (f.transfer) out.push(`中转（${f.flightNo}），全程 ${durationText(minutesOf(f))}`);
  if (f.nextDay) out.push("次日到达");
  const fit = tripFit(f, day, leg);
  if (fit && fit.tone !== "good") out.push(fit.text);
  return out;
}
