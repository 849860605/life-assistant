import type { HotelOption, ItineraryDay } from "../api/types";

/** 飞猪的档次写法归成四档，方便筛选 */
export const TIERS = ["经济", "舒适", "高档", "豪华"] as const;

export function tierOf(star: string | null): string | null {
  if (!star) return null;
  if (/豪华|五星/.test(star)) return "豪华";
  if (/高档|四星/.test(star)) return "高档";
  if (/舒适|三星/.test(star)) return "舒适";
  if (/经济|二星|一星/.test(star)) return "经济";
  return null;
}

export type HotelSort = "recommend" | "price" | "rating" | "distance";

/** 青旅、床位、胶囊这类：价格不能和普通酒店直接比，推荐时往后放 */
export function isHostel(h: HotelOption) {
  return /青年旅舍|青年旅社|青旅|青年公寓|国际青年|床位|胶囊/.test(h.name);
}

/** 球面直线距离（公里） */
export function km(aLng: number, aLat: number, bLng: number, bLat: number) {
  const r = (d: number) => (d * Math.PI) / 180;
  const dLat = r(bLat - aLat);
  const dLng = r(bLng - aLng);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(r(aLat)) * Math.cos(r(bLat)) * Math.sin(dLng / 2) ** 2;
  return 6371 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
}

/** 行程里有坐标的景点，去重 */
export function tripSpots(days: ItineraryDay[]) {
  const seen = new Set<string>();
  return days
    .flatMap((d, i) => d.items.filter((it) => it.type === "SPOT" && it.lng != null && it.lat != null).map((it) => ({ ...it, day: i + 1 })))
    .filter((it) => (seen.has(it.name) ? false : (seen.add(it.name), true)));
}

/**
 * 市区里的行程景点：离所有景点中位位置 15 公里以内的（兵马俑这类郊区一日游不算，不然每家酒店都被拉得一样远）。
 */
export function citySpots(days: ItineraryDay[]) {
  const spots = tripSpots(days);
  if (spots.length < 2) return spots;
  const mid = (xs: number[]) => [...xs].sort((a, b) => a - b)[Math.floor(xs.length / 2)];
  const cLng = mid(spots.map((s) => s.lng!));
  const cLat = mid(spots.map((s) => s.lat!));
  return spots.filter((s) => km(cLng, cLat, s.lng!, s.lat!) <= 15);
}

/** 离市区行程景点最近的那个景点名，当作找酒店的默认地标 */
export function centralSpot(days: ItineraryDay[]): string | null {
  const spots = citySpots(days);
  if (!spots.length) return null;
  let best = spots[0];
  let bestSum = Infinity;
  for (const s of spots) {
    const sum = spots.reduce((t, o) => t + km(s.lng!, s.lat!, o.lng!, o.lat!), 0);
    if (sum < bestSum) [best, bestSum] = [s, sum];
  }
  return best.name.replace(/[（(].*?[）)]/g, "").replace(/\s*[·・].*$/, "");
}

/** 到市区行程景点的平均直线距离（公里），没有景点为空 */
export function tripKm(h: HotelOption, days: ItineraryDay[]): number | null {
  const spots = citySpots(days);
  if (!spots.length || h.lng == null || h.lat == null) return null;
  return Math.round((spots.reduce((t, s) => t + km(h.lng!, h.lat!, s.lng!, s.lat!), 0) / spots.length) * 10) / 10;
}

export function sortHotels(list: HotelOption[], sort: HotelSort, days: ItineraryDay[]): HotelOption[] {
  const out = [...list];
  const price = (h: HotelOption) => h.price ?? 1e9;
  if (sort === "price") out.sort((a, b) => price(a) - price(b));
  else if (sort === "rating") out.sort((a, b) => (b.rating ?? 0) - (a.rating ?? 0) || price(a) - price(b));
  else if (sort === "distance") out.sort((a, b) => (a.distance ?? 1e9) - (b.distance ?? 1e9));
  else {
    // 推荐：离行程近、评分高、不比这批的中位价贵太多（便宜不加分，免得床位排最前），青旅往后放
    const prices = out.filter((h) => !isHostel(h)).map((h) => h.price).filter((p): p is number => p != null).sort((a, b) => a - b);
    const median = prices[Math.floor(prices.length / 2)] || 1;
    const score = (h: HotelOption) => {
      const d = tripKm(h, days) ?? (h.distance != null ? h.distance / 1000 : 5);
      const pricey = Math.max(0, (h.price ?? median) / median - 1);
      return (h.rating ?? 3.5) - d * 0.25 - pricey * 0.8 - (isHostel(h) ? 2 : 0);
    };
    out.sort((a, b) => score(b) - score(a));
  }
  return out;
}

export interface HotelPick {
  key: string;
  title: string;
  hotel: HotelOption;
}

/** 三个推荐：离行程最近、最便宜（评分 4 分以上）、档次最好（高档、豪华里最便宜的） */
export function hotelPicks(list: HotelOption[], days: ItineraryDay[]): HotelPick[] {
  const priced = list.filter((h) => h.price != null && !isHostel(h));
  if (!priced.length) return [];
  const picks: HotelPick[] = [];
  const add = (key: string, title: string, h: HotelOption | undefined) => {
    if (!h) return;
    const same = picks.find((p) => p.hotel.name === h.name);
    if (same) same.title += " · " + title;
    else picks.push({ key, title, hotel: h });
  };
  const near = [...priced].filter((h) => tripKm(h, days) != null).sort((a, b) => tripKm(a, days)! - tripKm(b, days)!)[0];
  add("near", days.length ? "离行程最近" : "离地标最近", near || [...priced].sort((a, b) => (a.distance ?? 1e9) - (b.distance ?? 1e9))[0]);
  add("cheap", "评分 4 分以上最便宜", [...priced].filter((h) => (h.rating ?? 0) >= 4).sort((a, b) => a.price! - b.price!)[0]);
  add("tier", "档次最好", [...priced].filter((h) => ["高档", "豪华"].includes(tierOf(h.star) || "")).sort((a, b) => a.price! - b.price!)[0]);
  return picks;
}

/** 推荐理由：全部按价格、距离、评分、档次、装修年份算，不让模型写 */
export function hotelReasons(h: HotelOption, list: HotelOption[], days: ItineraryDay[], anchor: string | null, nights: number) {
  const out: { tone: "good" | "bad"; text: string }[] = [];
  const priced = list.filter((x) => x.price != null).map((x) => x.price!).sort((a, b) => a - b);
  if (h.price != null && priced.length) {
    const rank = priced.filter((p) => p < h.price!).length + 1;
    out.push({ tone: "good", text: `每晚 ¥${h.price}，${nights} 晚约 ¥${h.price * nights}；在这 ${priced.length} 家里价格排第 ${rank}` });
  }
  if (h.distance != null && anchor) out.push({ tone: h.distance <= 1500 ? "good" : "bad", text: `离${anchor}直线 ${h.distance < 1000 ? h.distance + " 米" : (h.distance / 1000).toFixed(1) + " 公里"}${h.distance <= 1200 ? "，可以步行" : ""}` });
  const t = tripKm(h, days);
  if (t != null) out.push({ tone: t <= 4 ? "good" : "bad", text: `到市区行程景点平均直线 ${t} 公里` });
  if (h.rating != null) out.push({ tone: h.rating >= 4.5 ? "good" : h.rating >= 4 ? "good" : "bad", text: `飞猪评分 ${h.rating}` });
  else out.push({ tone: "bad", text: "飞猪上还没有评分" });
  if (h.star) out.push({ tone: "good", text: `档次：${h.star}` });
  if (isHostel(h)) out.push({ tone: "bad", text: "看名字是青旅或公寓床位，多为床位、小间，价格不能和普通酒店直接比" });
  if (h.renovated) {
    const age = new Date().getFullYear() - Number(h.renovated);
    out.push({ tone: age <= 3 ? "good" : "bad", text: age <= 0 ? `${h.renovated} 年新开或刚装修` : `${h.renovated} 年装修${age >= 6 ? "，有些年头了" : ""}` });
  }
  return out;
}
