import { ref } from "vue";
import { request } from "../api/http";

const KEY = "life_media_ticket";

/**
 * 下载票据：地图图片、PDF 链接里用它证明身份（这些地方带不了请求头），代替把登录 token 放进链接。
 * 后端签发，4 小时过期（改密码后也作废）；快过期时自动换新的。是响应式的，拿到票据后用到它的地图地址会自动刷新。
 */
export const mediaTicket = ref<string>(load());

let pending: Promise<string> | null = null;

function load(): string {
  try {
    const saved = JSON.parse(uni.getStorageSync(KEY) || "null") as { ticket: string; expiresAt: string } | null;
    // 还剩一小时以上才用
    return saved && new Date(saved.expiresAt).getTime() - Date.now() > 3600_000 ? saved.ticket : "";
  } catch {
    return "";
  }
}

/** 确保有可用的票据，返回票据；没登录或请求失败返回空串。 */
export function ensureMediaTicket(): Promise<string> {
  const current = load();
  if (current) {
    mediaTicket.value = current;
    return Promise.resolve(current);
  }
  if (!pending) {
    pending = request<{ ticket: string; expiresAt: string }>("GET", "/api/v1/auth/media-ticket")
      .then((t) => {
        uni.setStorageSync(KEY, JSON.stringify(t));
        mediaTicket.value = t.ticket;
        return t.ticket;
      })
      .catch(() => "")
      .finally(() => {
        pending = null;
      });
  }
  return pending;
}

/** 退出登录时清掉。 */
export function clearMediaTicket() {
  uni.removeStorageSync(KEY);
  mediaTicket.value = "";
}
