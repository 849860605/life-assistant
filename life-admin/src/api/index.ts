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

/** 模型提供方：Key 只回显后 4 位（keyTail），models 是它下面的模型数 */
export interface LlmProvider {
  id: number;
  code: string;
  name: string;
  type: "openai" | "dashscope";
  baseUrl: string | null;
  completionsPath: string | null;
  keyTail: string | null;
  hasKey: boolean;
  authHeader: string | null;
  noThinking: string | null;
  enabled: boolean;
  sort: number;
  models: number;
}

/** 新建、修改提供方：apiKey 留空表示不改 */
export interface LlmProviderForm {
  code: string;
  name: string;
  type: string;
  baseUrl: string;
  completionsPath: string;
  apiKey: string;
  authHeader: string;
  noThinking: string;
  enabled: boolean;
  sort: number;
}

/** 模型：单价是元 / 百万 Token，空表示不算费用 */
export interface LlmModel {
  id: number;
  providerCode: string;
  model: string;
  displayName: string;
  description: string | null;
  enabled: boolean;
  userSelectable: boolean;
  supportsTools: boolean;
  priceInput: number | null;
  priceCachedInput: number | null;
  priceOutput: number | null;
  sort: number;
}

export type LlmModelForm = Omit<LlmModel, "id">;

/** 测试连接的结果 */
export interface LlmTestResult {
  ok: boolean;
  millis: number;
  model: string;
  reply: string | null;
  error: string | null;
}

/** 某个工具或平台近几天的调用统计（后端原样返回的列名） */
export type CallStat = Record<string, string | number | null>;

/** 第三方平台的一个字段：敏感字段只给 masked（如 ••••a1b2）；source 是生效值来源（ADMIN 后台、ENV 是 .env） */
export interface IntegrationField {
  name: string;
  label: string;
  kind: "SECRET" | "TEXT" | "READONLY";
  env: string | null;
  placeholder: string | null;
  value: string | null;
  masked: string | null;
  source: "ADMIN" | "ENV" | null;
  envValue: boolean;
}

/** 一个第三方平台：status 是 ON（启用且配好）、UNSET（启用了但缺 Key 或地址）、OFF（停用） */
export interface Integration {
  code: string;
  name: string;
  category: string;
  description: string;
  impact: string;
  testNote: string | null;
  keyOptional: boolean;
  enabled: boolean;
  status: "ON" | "UNSET" | "OFF";
  fields: IntegrationField[];
  calls7d: number | null;
  failures7d: number | null;
  avgMs: number | null;
  updatedAt: string | null;
}

export interface IntegrationTest {
  ok: boolean;
  ms: number;
  message: string;
}

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

  llmProviders: () => request<LlmProvider[]>("GET", "/llm/providers"),
  createLlmProvider: (body: LlmProviderForm) => request<void>("POST", "/llm/providers", body),
  updateLlmProvider: (code: string, body: LlmProviderForm) => request<void>("PUT", `/llm/providers/${code}`, body),
  deleteLlmProvider: (code: string) => request<void>("DELETE", `/llm/providers/${code}`),
  testLlmProvider: (code: string, model?: string) => request<LlmTestResult>("POST", `/llm/providers/${code}/test`, { model }),
  llmModels: () => request<LlmModel[]>("GET", "/llm/models"),
  createLlmModel: (body: LlmModelForm) => request<LlmModel>("POST", "/llm/models", body),
  updateLlmModel: (id: number, body: LlmModelForm) => request<LlmModel>("PUT", `/llm/models/${id}`, body),
  deleteLlmModel: (id: number) => request<void>("DELETE", `/llm/models/${id}`),
  llmRoles: () => request<Record<string, number | null>>("GET", "/llm/roles"),
  assignLlmRole: (role: string, modelId: number) => request<void>("PUT", `/llm/roles/${role}`, { modelId }),

  opLogs: (p: { action?: string; page: number; size: number }) => request<Page<OpLog>>("GET", "/op-logs" + qs(p)),
  opActions: () => request<string[]>("GET", "/op-logs/actions"),

  integrations: () => request<Integration[]>("GET", "/integrations"),
  updateIntegration: (code: string, values: Record<string, string>, clear: string[]) =>
    request<Integration>("PUT", `/integrations/${code}`, { values, clear }),
  setIntegrationEnabled: (code: string, enabled: boolean) =>
    request<Integration>("POST", `/integrations/${code}/status`, { enabled }),
  testIntegration: (code: string) => request<IntegrationTest>("POST", `/integrations/${code}/test`),
};
