import { ElMessage } from "element-plus";

/** 管理端 token 存在 localStorage，和用户端（H5 里的 life_token）分开 */
const TOKEN_KEY = "xinglo_admin_token";

export function getToken(): string {
  return localStorage.getItem(TOKEN_KEY) || "";
}

export function setToken(token: string) {
  if (token) localStorage.setItem(TOKEN_KEY, token);
  else localStorage.removeItem(TOKEN_KEY);
}

/** 接口返回的错误，message 是后端给的中文提示 */
export class ApiError extends Error {
  constructor(public code: number, message: string) {
    super(message);
  }
}

/** 登录失效时的回调（路由那边设成跳到登录页） */
let onUnauthorized: () => void = () => {};
export function setUnauthorizedHandler(fn: () => void) {
  onUnauthorized = fn;
}

/**
 * 调管理端接口：自动带 token、解包 { code, message, data }；401 时清掉 token 并回到登录页。
 * quiet 为 true 时不自动弹错误提示，由调用方自己处理。
 */
export async function request<T>(method: string, path: string, body?: unknown, quiet = false): Promise<T> {
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  const token = getToken();
  if (token) headers.Authorization = `Bearer ${token}`;
  let res: Response;
  try {
    res = await fetch(`/admin/api/v1${path}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  } catch {
    const err = new ApiError(0, "连不上服务器，检查网络后再试");
    if (!quiet) ElMessage.error(err.message);
    throw err;
  }
  const json = await res.json().catch(() => null);
  if (res.status === 401 || json?.code === 401) {
    const loggedIn = !!token;
    setToken("");
    if (loggedIn) onUnauthorized();
    const err = new ApiError(401, json?.message || "请重新登录");
    if (!quiet && loggedIn) ElMessage.warning("登录已失效，请重新登录");
    throw err;
  }
  if (!res.ok || !json || json.code !== 0) {
    const err = new ApiError(json?.code ?? res.status, json?.message || `请求失败（${res.status}）`);
    if (!quiet) ElMessage.error(err.message);
    throw err;
  }
  return json?.data as T;
}
