import type { ApiResponse } from "./types";

const TOKEN_KEY = "life_token";

/** 接口地址前缀：开发时为空走 Vite 代理，部署到其他域名时用 VITE_API_BASE 指定。 */
export const API_BASE: string = import.meta.env.VITE_API_BASE || "";

export function getToken(): string {
  return uni.getStorageSync(TOKEN_KEY) || "";
}

export function setToken(token: string) {
  uni.setStorageSync(TOKEN_KEY, token);
}

/** 清掉登录态并回到登录页；已经在登录页就不再跳，避免重建页面把输入框清空。 */
export function toLogin() {
  uni.removeStorageSync(TOKEN_KEY);
  // 下载票据也一起清掉（utils/media.ts 里的 key）
  uni.removeStorageSync("life_media_ticket");
  const pages = getCurrentPages();
  if (pages.length && pages[pages.length - 1].route === "pages/login/login") return;
  uni.reLaunch({ url: "/pages/login/login" });
}

/** 后端返回的业务错误，message 可以直接展示给用户。 */
export class ApiError extends Error {
  constructor(public code: number, message: string) {
    super(message);
  }
}

/** 发 JSON 请求：自动带 token，未登录跳登录页，业务失败抛 ApiError。 */
export function request<T>(method: "GET" | "POST" | "PUT" | "DELETE", url: string, data?: unknown): Promise<T> {
  return new Promise((resolve, reject) => {
    uni.request({
      url: API_BASE + url,
      method,
      data: data as any,
      header: { Authorization: `Bearer ${getToken()}`, "Content-Type": "application/json" },
      success: (res) => {
        const body = res.data as ApiResponse<T>;
        if (res.statusCode === 401) {
          toLogin();
          reject(new ApiError(401, body?.message || "请先登录"));
          return;
        }
        if (!body || typeof body !== "object" || body.code !== 0) {
          reject(new ApiError(body?.code ?? res.statusCode, body?.message || `请求失败（${res.statusCode}）`));
          return;
        }
        resolve(body.data);
      },
      fail: (err) => reject(new ApiError(-1, "网络连不上后端：" + (err.errMsg || ""))),
    });
  });
}

/** 弹出错误提示；未登录已经在跳登录页，不再提示。 */
export function showError(e: unknown) {
  if (e instanceof ApiError && e.code === 401) return;
  const message = e instanceof Error ? e.message : String(e);
  uni.showToast({ title: message, icon: "none", duration: 3000 });
}
