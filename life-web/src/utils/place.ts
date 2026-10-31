import { API_BASE } from "../api/http";
import { ensureMediaTicket, mediaTicket } from "./media";
import type { ItineraryDay, ItineraryItem, ItineraryLeg } from "../api/types";

const CITY_KEY = "life_city";
/** 还没选过城市时的默认城市 */
export const DEFAULT_CITY = "西安";

/** 当前城市：顶栏选的，存在本地。 */
export function currentCity(): string {
  return uni.getStorageSync(CITY_KEY) || DEFAULT_CITY;
}

export function setCurrentCity(city: string) {
  uni.setStorageSync(CITY_KEY, city);
}

/** 行程目的地取城市部分："深圳·梧桐山蝴蝶谷" → "深圳"。 */
export function cityOf(destination?: string | null): string {
  return (destination || "").split(/[·・\s,，]/)[0] || "";
}

/** 地图上最多标的点数，和后端一致 */
const MAX_MAP_POINTS = 10;

/**
 * 地图图片地址（后端代理高德静态地图）。stops 按顺序标 1、2、3，route 为 true 时画驾车路线；
 * others 是橙色候选点，各最多 10 个。<image> 带不了请求头，用下载票据证明身份；
 * 票据还没拿到时返回空串，拿到后用到它的计算属性会自动重算。
 */
export function mapUrl(opts: { stops?: number[][]; others?: number[][]; route?: boolean; w?: number; h?: number }): string {
  const ticket = mediaTicket.value;
  if (!ticket) {
    ensureMediaTicket();
    return "";
  }
  const join = (pts?: number[][]) => (pts || []).slice(0, MAX_MAP_POINTS).map((p) => `${p[0].toFixed(6)},${p[1].toFixed(6)}`).join(";");
  const params = [
    opts.stops?.length ? `stops=${encodeURIComponent(join(opts.stops))}` : "",
    opts.others?.length ? `others=${encodeURIComponent(join(opts.others))}` : "",
    opts.route ? "route=true" : "",
    `w=${opts.w || 600}`,
    `h=${opts.h || 400}`,
    `mt=${encodeURIComponent(ticket)}`,
  ].filter(Boolean);
  return `${API_BASE}/api/v1/maps/static?${params.join("&")}`;
}

/** 一天里有坐标的项，按顺序。 */
export function located(day?: ItineraryDay | null): ItineraryItem[] {
  return (day?.items || []).filter((i) => i.lng != null && i.lat != null);
}

/** 一天的路线图地址；没有带坐标的项返回空串。 */
export function dayMapUrl(day: ItineraryDay | null | undefined, w = 600, h = 400): string {
  const stops = located(day).map((i) => [i.lng!, i.lat!]);
  return stops.length ? mapUrl({ stops, route: stops.length > 1, w, h }) : "";
}

/** 从 fromId 到下一个有坐标的项的路程。 */
export function legFrom(day: ItineraryDay | null | undefined, fromId: string): ItineraryLeg | undefined {
  return day?.legs?.find((l) => l.from === fromId);
}

/** 路程的文字："打车 25 分钟 · 12 公里" / "步行 8 分钟"。 */
export function legText(leg?: ItineraryLeg): string {
  if (!leg) return "";
  return leg.mode === "WALK" ? `步行 ${leg.minutes} 分钟` : `打车 ${leg.minutes} 分钟 · ${leg.km} 公里`;
}

/** 一天的路程汇总：总分钟、总公里。 */
export function dayTravel(day?: ItineraryDay | null): { minutes: number; km: number } {
  const legs = day?.legs || [];
  return {
    minutes: legs.reduce((s, l) => s + l.minutes, 0),
    km: Math.round(legs.reduce((s, l) => s + l.km, 0) * 10) / 10,
  };
}

/** 分钟显示成"1 小时 20 分"。 */
export function duration(minutes: number): string {
  if (minutes < 60) return `${minutes} 分钟`;
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return m ? `${h} 小时 ${m} 分` : `${h} 小时`;
}

/** 米数显示成"800m"或"2.3km"。 */
export function distanceText(meters?: number | null): string {
  if (meters == null) return "";
  return meters < 1000 ? `${meters}m` : `${(meters / 1000).toFixed(1)}km`;
}

/**
 * 在高德地图里打开一个地点（高德官方的跳转链接，不需要 Key）：手机上装了高德 App 会直接打开 App，
 * 没装或在电脑上打开高德网页版。有坐标时标出这个点，没有坐标时按名称在城市里搜。
 */
export function amapUrl(p: { name: string; lng?: number | null; lat?: number | null }, city?: string): string {
  const name = encodeURIComponent(p.name);
  if (p.lng != null && p.lat != null) {
    return `https://uri.amap.com/marker?position=${p.lng},${p.lat}&name=${name}&coordinate=gaode&callnative=1&src=life-assistant`;
  }
  return `https://uri.amap.com/search?keyword=${name}${city ? `&city=${encodeURIComponent(city)}` : ""}&callnative=1&src=life-assistant`;
}

/** 用高德导航到这个地点（从当前位置出发，默认驾车）；没有坐标时退回按名称搜。 */
export function amapNavUrl(p: { name: string; lng?: number | null; lat?: number | null }, city?: string): string {
  if (p.lng == null || p.lat == null) return amapUrl(p, city);
  return `https://uri.amap.com/navigation?to=${p.lng},${p.lat},${encodeURIComponent(p.name)}&mode=car&coordinate=gaode&callnative=1&src=life-assistant`;
}
