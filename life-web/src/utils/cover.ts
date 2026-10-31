import { api } from "../api";
import type { Place } from "../api/types";

/** 城市热门景点按城市缓存在内存里，页面之间切换（reLaunch）不重复请求。 */
const spotCache = new Map<string, Promise<Place[]>>();

/** 城市的热门景点（高德，按评分排），失败返回空列表。 */
export function citySpots(city: string, size = 6): Promise<Place[]> {
  const key = `${city}|${size}`;
  if (!spotCache.has(key)) {
    spotCache.set(key, api.places({ kind: "spot", city, size }).catch(() => {
      spotCache.delete(key);
      return [];
    }));
  }
  return spotCache.get(key)!;
}

/** 城市封面：第一个有照片的热门景点的照片，没有返回空串（页面显示渐变占位）。 */
export async function cityCover(city?: string | null): Promise<string> {
  if (!city) return "";
  const spots = await citySpots(city, 3);
  return spots.find((p) => p.photo)?.photo || "";
}
