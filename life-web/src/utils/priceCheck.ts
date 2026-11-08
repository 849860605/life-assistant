import type { Itinerary, ItineraryItem } from "../api/types";

/** 后台补比价超过这么久还没写回，当作失败，不再显示"比价中" */
const STALE_MS = 3 * 60 * 1000;

/** 航班号：两位航司代码加三四位数字，如 MU5402（不用后行断言，老版本 iOS 不支持） */
const FLIGHT_NO = /(?:^|[^A-Z0-9])([A-Z0-9]{2}\d{3,4})(?!\d)/;

/** 一段文字里的航班号（首页从今天的安排里找航班用），没有返回空 */
export function flightNoIn(text: string | null | undefined): string | null {
  return text?.match(FLIGHT_NO)?.[1] || null;
}

/** 交通项名称里的航班号，不是航班返回空 */
export function flightNoOf(item: ItineraryItem): string | null {
  if (item.type !== "TRANSPORT") return null;
  return item.name.match(FLIGHT_NO)?.[1] || null;
}

/** 这个键（酒店"名称|入住日期"、航班"航班号|日期"）是不是还在后台比价中 */
export function isChecking(checking: Record<string, string> | null | undefined, key: string): boolean {
  const at = checking?.[key];
  return !!at && Date.now() - new Date(at).getTime() < STALE_MS;
}

/** 行程里还有没有正在后台比价的项（页面据此隔几秒刷新一次） */
export function anyChecking(it: Pick<Itinerary, "checking"> | null | undefined): boolean {
  return Object.keys(it?.checking || {}).some((k) => isChecking(it!.checking, k));
}

/** 住宿是否在比价中 */
export function stayChecking(it: Pick<Itinerary, "checking"> | null | undefined, stay: { name: string; checkIn: string }) {
  return isChecking(it?.checking, `${stay.name}|${stay.checkIn}`);
}

/** 航班项是否在比价中 */
export function flightChecking(it: Pick<Itinerary, "checking"> | null | undefined, item: ItineraryItem, date: string) {
  const no = flightNoOf(item);
  return !!no && isChecking(it?.checking, `${no}|${date}`);
}

/**
 * 有项在后台比价时每隔几秒调一次 refresh，直到 done 返回 true 或超过 3 分钟；返回停止函数。
 */
export function pollWhileChecking(refresh: () => Promise<boolean>, everyMs = 5000): () => void {
  const until = Date.now() + STALE_MS;
  let stopped = false;
  let timer: ReturnType<typeof setTimeout> | null = null;
  const tick = async () => {
    if (stopped) return;
    let done = false;
    try {
      done = await refresh();
    } catch {
      // 刷新失败下次再试
    }
    if (!done && Date.now() < until && !stopped) timer = setTimeout(tick, everyMs);
  };
  timer = setTimeout(tick, everyMs);
  return () => {
    stopped = true;
    if (timer) clearTimeout(timer);
  };
}
