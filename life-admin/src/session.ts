import { reactive } from "vue";
import { api, type Admin } from "./api";
import { getToken, setToken } from "./api/http";

/** 当前登录的管理员；页面刷新后用 token 重新取一次 */
export const session = reactive<{ admin: Admin | null }>({ admin: null });

/** 有 token 但还没取过管理员信息时取一次；token 失效返回 null。 */
export async function ensureAdmin(): Promise<Admin | null> {
  if (session.admin) return session.admin;
  if (!getToken()) return null;
  try {
    session.admin = await api.me();
  } catch {
    session.admin = null;
  }
  return session.admin;
}

/** 登录成功：记下 token 和管理员 */
export function signIn(token: string, admin: Admin) {
  setToken(token);
  session.admin = admin;
}

/** 退出：通知后端注销 token（失败也不管），清掉本地登录态 */
export async function signOut() {
  await api.logout().catch(() => undefined);
  setToken("");
  session.admin = null;
}

export function isSuper(): boolean {
  return session.admin?.role === "SUPER";
}
