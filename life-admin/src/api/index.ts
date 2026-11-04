import { request } from "./http";

/** 管理员：role 是 SUPER（超级管理员）或 ADMIN（普通管理员） */
export interface Admin {
  id: number;
  username: string;
  nickname: string | null;
  role: "SUPER" | "ADMIN";
  enabled: boolean;
  lastLoginAt: string | null;
  createdAt: string;
}

export interface Page<T> {
  total: number;
  items: T[];
}

/** 用户列表的一行：最近活跃是最近一次对话的时间 */
export interface UserRow {
  id: number;
  username: string;
  nickname: string | null;
  status: number;
  createdAt: string;
  lastLoginAt: string | null;
  lastActiveAt: string | null;
  conversations: number;
  trips: number;
}

/** 用户概要：只有计数和时间，没有对话正文、行程内容 */
export interface UserSummary {
  conversations: number;
  messages: number;
  trips: number;
  events: number;
  memories: number;
  llmCalls30d: number;
  tokens30d: number;
  cost30d: number;
  lastActiveAt: string | null;
}

export interface UserDetail {
  id: number;
  username: string;
  nickname: string | null;
  timezone: string;
  enabled: boolean;
  createdAt: string;
  lastLoginAt: string | null;
  summary: UserSummary;
}

export interface DayStat {
  date: string;
  conversations: number;
  llmCalls: number;
  tokens: number;
  cost: number;
}

export interface Overview {
  users: number;
  activeUsers: number;
  admins: number;
  conversationsToday: number;
  conversations7d: number;
  messagesToday: number;
  llmCallsToday: number;
  costToday: number;
  cost7d: number;
  tokens7d: number;
  toolCalls7d: number;
  toolSuccessRate7d: number | null;
  activeUsers7d: number;
  days: DayStat[];
}

export interface OpLog {
  id: number;
  adminId: number;
  adminName: string | null;
  action: string;
  target: string | null;
  ip: string | null;
  createdAt: string;
}

/** 某个工具或平台近几天的调用统计（后端原样返回的列名） */
export type CallStat = Record<string, string | number | null>;

function qs(params: Record<string, string | number | undefined | null>): string {
  const parts = Object.entries(params)
    .filter(([, v]) => v !== undefined && v !== null && v !== "")
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`);
  return parts.length ? `?${parts.join("&")}` : "";
}

export const api = {
  login: (username: string, password: string) =>
    request<{ token: string; admin: Admin }>("POST", "/auth/login", { username, password }, true),
  logout: () => request<void>("POST", "/auth/logout", undefined, true),
  me: () => request<Admin>("GET", "/auth/me", undefined, true),
  changePassword: (oldPassword: string, newPassword: string) =>
    request<void>("POST", "/auth/password", { oldPassword, newPassword }),

  overview: () => request<Overview>("GET", "/overview"),
  callStats: (days = 7) => request<CallStat[]>("GET", `/calls/stats?days=${days}`),

  users: (p: { keyword?: string; page: number; size: number }) => request<Page<UserRow>>("GET", "/users" + qs(p)),
  user: (id: number) => request<UserDetail>("GET", `/users/${id}`),
  createUser: (body: { username: string; password: string; nickname?: string }) => request<UserDetail>("POST", "/users", body),
  resetUserPassword: (id: number, password: string) => request<void>("POST", `/users/${id}/password`, { password }),
  setUserEnabled: (id: number, enabled: boolean) => request<UserDetail>("POST", `/users/${id}/status`, { enabled }),

  admins: () => request<Admin[]>("GET", "/admins"),
  createAdmin: (body: { username: string; password: string; role: string; nickname?: string }) =>
    request<Admin>("POST", "/admins", body),
  resetAdminPassword: (id: number, password: string) => request<void>("POST", `/admins/${id}/password`, { password }),
  setAdminEnabled: (id: number, enabled: boolean) => request<Admin>("POST", `/admins/${id}/status`, { enabled }),

  opLogs: (p: { action?: string; page: number; size: number }) => request<Page<OpLog>>("GET", "/op-logs" + qs(p)),
  opActions: () => request<string[]>("GET", "/op-logs/actions"),
};
