import { ref } from "vue";
import { api } from "../api";
import { showError } from "../api/http";
import type { Favorite } from "../api/types";

/** 已收藏的东西，键是"类型:refKey"；第一次用到时从后端取一次，之后收藏、取消在这里同步 */
export const favs = ref<Record<string, Favorite>>({});
let loading: Promise<void> | null = null;

export function favKey(kind: string, refKey: string): string {
  return `${kind}:${refKey}`;
}

export function loadFavorites(force = false): Promise<void> {
  if (loading && !force) return loading;
  loading = api.favorites().then((list) => {
    favs.value = Object.fromEntries(list.map((f) => [favKey(f.kind, f.refKey), f]));
  }).catch(() => {
    loading = null;
  });
  return loading;
}

/** 收藏或取消收藏，返回之后是不是收藏状态 */
export async function toggleFavorite(item: Omit<Favorite, "id" | "createdAt">): Promise<boolean> {
  const key = favKey(item.kind, item.refKey);
  const existing = favs.value[key];
  try {
    if (existing) {
      await api.removeFavorite(existing.id);
      const next = { ...favs.value };
      delete next[key];
      favs.value = next;
      uni.showToast({ title: "已取消收藏", icon: "none" });
      return false;
    }
    const saved = await api.addFavorite(item);
    favs.value = { ...favs.value, [key]: saved };
    uni.showToast({ title: "已收藏，在右上角「收藏」里看", icon: "none" });
    return true;
  } catch (e) {
    showError(e);
    return !!existing;
  }
}
