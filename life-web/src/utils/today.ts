import type { AgendaEntry } from "../api/types";
import { openUrl } from "./format";

const MODE_TEXT: Record<string, string> = { DRIVE: "车程", WALK: "步行", TRANSIT: "公交地铁" };

/** 建议出发的一行字："建议 18:20 从家出发 · 车程约 25 分钟"；没算出来返回空串。 */
export function leaveText(e: AgendaEntry): string {
  if (e.past || e.minutes == null) return "";
  const how = `${MODE_TEXT[e.mode || "DRIVE"] || "路上"}约 ${e.minutes} 分钟`;
  const from = e.from ? `从${e.from.length > 10 ? e.from.slice(0, 10) + "…" : e.from}` : "";
  return e.leaveAt ? `建议 ${e.leaveAt} ${from}出发 · ${how}` : `${from}过去 · ${how}`;
}

/** 能不能导航、打车：有坐标、还没过去 */
export function canGo(e: AgendaEntry): boolean {
  return e.lng != null && e.lat != null && !e.past;
}

/** 高德路线（终点填好，起点是当前位置）：mode 是 car、walk、bus；手机上装了高德会直接打开 App。 */
function amapRoute(e: AgendaEntry, mode: string): string {
  const name = encodeURIComponent(e.place || e.title);
  return `https://uri.amap.com/navigation?to=${e.lng},${e.lat},${name}&mode=${mode}&coordinate=gaode&callnative=1&src=xinglo`;
}

/** 导航：按这段路本来的走法（步行段用步行，其余开车）打开高德。 */
export function navigate(e: AgendaEntry) {
  openUrl(amapRoute(e, e.mode === "WALK" ? "walk" : e.mode === "TRANSIT" ? "bus" : "car"));
}

/**
 * 打车：打开高德的驾车路线页，终点填好，在页面上点"打车"叫车；叫车、付款都在高德里自己确认，这里不下单。
 */
export function takeTaxi(e: AgendaEntry) {
  openUrl(amapRoute(e, "car"));
}

/** 打车按钮上的字：有估价时带上，如"打车 ¥11" */
export function taxiText(e: AgendaEntry): string {
  return e.taxi ? `打车 ¥${e.taxi}` : "打车";
}

/**
 * 从首页点日程进日程页时，要打开详情的那一项（日程页是底部标签页，不能带参数跳转）；
 * entry 是首页上的这一项，带着查好的坐标和路程，详情里直接能导航。
 */
let pending: { id: number; entry?: AgendaEntry } | null = null;

export function openEventLater(id: number, entry?: AgendaEntry) {
  pending = { id, entry };
}

export function takePendingEvent(): { id: number; entry?: AgendaEntry } | null {
  const p = pending;
  pending = null;
  return p;
}

/** 没有坐标时在高德里搜这个地点（用户在高德里选中后再导航） */
export function searchPlace(place: string, city?: string) {
  openUrl(`https://uri.amap.com/search?keyword=${encodeURIComponent(place)}${city ? `&city=${encodeURIComponent(city)}` : ""}&callnative=1&src=xinglo`);
}
