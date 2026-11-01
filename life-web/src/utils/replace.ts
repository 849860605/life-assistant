import type { Place, PlacePick } from "../api/types";

/** 替换时可选的偏好（多选，分别查高德再合并），按要换的类型给 */
export const PREFS: Record<string, string[]> = {
  SPOT: ["历史文化", "自然风光", "博物馆", "寺庙古建", "网红打卡", "亲子", "夜景", "公园"],
  FOOD: ["本地菜", "小吃", "火锅", "面馆", "清真", "西餐", "咖啡甜品"],
  HOTEL: ["经济型", "四星", "五星", "民宿"],
};

export type PlaceSort = "综合推荐" | "距离最近" | "评分最高";

/**
 * 候选排序。综合推荐：高德评分为主，每远 1 公里扣 0.15 分（没评分按 3.5 算），免得推荐太远的。
 */
export function sortPlaces(list: Place[], sort: PlaceSort): Place[] {
  const km = (p: Place) => (p.distance ?? 0) / 1000;
  const score = (p: Place) => (p.rating ?? 3.5) - km(p) * 0.15;
  const sorted = [...list];
  if (sort === "距离最近") sorted.sort((a, b) => (a.distance ?? 1e9) - (b.distance ?? 1e9));
  else if (sort === "评分最高") sorted.sort((a, b) => (b.rating ?? 0) - (a.rating ?? 0));
  else sorted.sort((a, b) => score(b) - score(a));
  return sorted;
}

/** 候选地点转成换上去的项 */
export function pickOf(p: Place): PlacePick {
  return { name: p.name, poiId: p.id, address: p.address, lng: p.lng, lat: p.lat, photo: p.photo, rating: p.rating, cost: p.cost };
}
