import { API_BASE } from "../api/http";
import { ensureMediaTicket, mediaTicket } from "./media";
import type { DayTotals, ItineraryDay, ItineraryItem, ItineraryLeg } from "../api/types";

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

/** 可点地图上的一个编号标记 */
export interface MapStop {
  id: string;
  name: string;
  lng: number;
  lat: number;
}

/** 地图上额外画的一条线（其他几天的路线）：颜色是 6 位十六进制 */
export interface MapLine {
  color: string;
  points: number[][];
}

/**
 * 地图图片地址（后端代理高德静态地图）。stops 按顺序标 1、2、3，route 为 true 时画驾车路线；
 * others 是橙色候选点，各最多 10 个。color 是路线颜色，circle 是以第一个 stop 为圆心的范围圈（米），
 * lines 是其他几天的路线；center + zoom 指定显示范围，marks 为 false 时不画 stops 的标记（页面自己叠）。
 * <image> 带不了请求头，用下载票据证明身份；票据还没拿到时返回空串，拿到后用到它的计算属性会自动重算。
 */
export function mapUrl(opts: {
  stops?: number[][]; others?: number[][]; route?: boolean; w?: number; h?: number; color?: string; circle?: number;
  lines?: MapLine[]; center?: number[]; zoom?: number; marks?: boolean;
}): string {
  const ticket = mediaTicket.value;
  if (!ticket) {
    ensureMediaTicket();
    return "";
  }
  const join = (pts?: number[][]) => (pts || []).slice(0, MAX_MAP_POINTS).map((p) => `${p[0].toFixed(6)},${p[1].toFixed(6)}`).join(";");
  const lines = (opts.lines || []).filter((l) => l.points.length > 1).slice(0, 6);
  const params = [
    opts.stops?.length ? `stops=${encodeURIComponent(join(opts.stops))}` : "",
    opts.others?.length ? `others=${encodeURIComponent(join(opts.others))}` : "",
    opts.route ? "route=true" : "",
    opts.color ? `color=${opts.color}` : "",
    opts.circle ? `circle=${Math.round(opts.circle)}` : "",
    lines.length ? `lines=${encodeURIComponent(lines.map((l) => `${l.color}:${join(l.points)}`).join("|"))}` : "",
    opts.center && opts.zoom ? `center=${encodeURIComponent(join([opts.center]))}&zoom=${opts.zoom}` : "",
    opts.marks === false ? "marks=false" : "",
    `w=${opts.w || 600}`,
    `h=${opts.h || 400}`,
    `mt=${encodeURIComponent(ticket)}`,
  ].filter(Boolean);
  return `${API_BASE}/api/v1/maps/static?${params.join("&")}`;
}

/** 墨卡托投影下某个缩放级别的世界像素坐标。实测高德静态地图的 zoom 比常见的 256 像素瓦片约定大一级，所以按 512 算。 */
function worldPx(p: number[], zoom: number): number[] {
  const scale = 512 * 2 ** zoom;
  const sin = Math.min(Math.max(Math.sin((p[1] * Math.PI) / 180), -0.9999), 0.9999);
  return [((p[0] + 180) / 360) * scale, (0.5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI)) * scale];
}

/** 能把这些点都放进 w×h（四周留 pad 像素）的最大缩放级别和中心点；没有点返回空。 */
export function fitView(points: number[][], w: number, h: number, pad = 48): { center: number[]; zoom: number } | null {
  if (!points.length) return null;
  const lngs = points.map((p) => p[0]);
  const lats = points.map((p) => p[1]);
  const sw = [Math.min(...lngs), Math.min(...lats)];
  const ne = [Math.max(...lngs), Math.max(...lats)];
  const center = [(sw[0] + ne[0]) / 2, (sw[1] + ne[1]) / 2];
  for (let zoom = 17; zoom > 3; zoom--) {
    const a = worldPx(sw, zoom);
    const b = worldPx(ne, zoom);
    if (Math.abs(b[0] - a[0]) <= w - pad * 2 && Math.abs(b[1] - a[1]) <= h - pad * 2) {
      return { center, zoom: Math.min(zoom, 16) };
    }
  }
  return { center, zoom: 4 };
}

/** 一个点在 w×h 的地图图片上的位置（百分比），超出图片范围返回空。 */
export function projectPct(p: number[], center: number[], zoom: number, w: number, h: number): { x: number; y: number } | null {
  const a = worldPx(p, zoom);
  const c = worldPx(center, zoom);
  const x = (a[0] - c[0] + w / 2) / w;
  const y = (a[1] - c[1] + h / 2) / h;
  return x < 0 || x > 1 || y < 0 || y > 1 ? null : { x: x * 100, y: y * 100 };
}

/** 按直线距离估的路上时间："步行约 8 分钟" / "车程约 15 分钟"（规则和行程里的路程一致，只是估算）。 */
export function travelGuess(meters?: number | null): string {
  if (meters == null) return "";
  const km = meters / 1000;
  return km <= 1.2 ? `步行约 ${Math.max(1, Math.round((km * 1.3) / 4.5 * 60))} 分钟` : `车程约 ${Math.round((km * 1.4) / 30 * 60) + 5} 分钟`;
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

/** 一天的路程汇总：总分钟、总公里、打车费（驾车段都没查到打车费时为空）。 */
export function dayTravel(day?: ItineraryDay | null): { minutes: number; km: number; taxi: number | null } {
  const legs = day?.legs || [];
  const taxis = legs.filter((l) => l.taxi != null);
  return {
    minutes: legs.reduce((s, l) => s + l.minutes, 0),
    km: Math.round(legs.reduce((s, l) => s + l.km, 0) * 10) / 10,
    taxi: taxis.length ? taxis.reduce((s, l) => s + (l.taxi || 0), 0) : null,
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

/** 前后对比的一行：变好 tone 为 good，变差为 bad */
export interface CompareRow {
  label: string;
  before: string;
  after: string;
  delta: string;
  tone: "" | "good" | "bad";
}

/** 改动前后一天路上合计的对比（数值都来自高德）：路上时间、里程、打车费、结束时间。 */
export function compareTotals(b: DayTotals, a: DayTotals): CompareRow[] {
  const row = (label: string, before: number | null, after: number | null, fmt: (n: number) => string, unit: (n: number) => string): CompareRow => {
    if (before == null || after == null) return { label, before: before == null ? "—" : fmt(before), after: after == null ? "—" : fmt(after), delta: "", tone: "" };
    const d = Math.round((after - before) * 10) / 10;
    return { label, before: fmt(before), after: fmt(after), delta: d === 0 ? "不变" : (d < 0 ? "少 " : "多 ") + unit(Math.abs(d)), tone: d < 0 ? "good" : d > 0 ? "bad" : "" };
  };
  return [
    row("路上时间", b.minutes, a.minutes, (n) => duration(n), (n) => `${n} 分钟`),
    row("里程", b.km, a.km, (n) => `${n} 公里`, (n) => `${n} 公里`),
    row("打车费", b.taxi, a.taxi, (n) => `约 ¥${n}`, (n) => `¥${n}`),
    { label: "结束时间", before: b.endsAt || "—", after: a.endsAt || "—", delta: "", tone: "" },
  ];
}
