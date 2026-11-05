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
  /** 绑定的邮箱，没绑为空 */
  email: string | null;
  /** 账号来源：ADMIN 后台建的，EMAIL 自己用邮箱注册的 */
  source: string;
  status: number;
  createdAt: string;
  lastLoginAt: string | null;
  lastActiveAt: string | null;
  conversations: number;
  trips: number;
  /** 会员到期时间，从没开通过为空 */
  memberUntil: string | null;
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
  email: string | null;
  source: string;
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

/** 支付宝订单的退款请求 */
export interface PayRefund {
  outRequestNo: string;
  outTradeNo: string;
  refundAmount: string;
  status: "PENDING" | "SUCCESS" | "FAILED";
  message?: string | null;
  createdAt?: string | null;
}

/** 支付宝订单：status 为 WAIT_PAY / PAID / CLOSED */
export interface PayOrder {
  outTradeNo: string;
  subject: string;
  totalAmount: string;
  status: "WAIT_PAY" | "PAID" | "CLOSED";
  tradeNo?: string | null;
  paidAt?: string | null;
  createdAt?: string | null;
  refundedAmount: string;
  refunds: PayRefund[];
}

/** 订单列表的一行：下单用户加订单 */
export interface OrderRow {
  userId: number;
  username?: string | null;
  nickname?: string | null;
  order: PayOrder;
}

/** 会员设置：月度价格、一次多少天、支付宝收款配好了没有，免费用户和会员每天能对话多少次 */
export interface MemberSetting {
  price: string;
  days: number;
  payReady: boolean;
  freeDaily: number;
  memberDaily: number;
}

/** 一条会员变动记录：source 为 PAY 付费开通 / ADMIN 后台调整，days 为负是扣减 */
export interface MemberLog {
  id: number;
  userId: number;
  days: number;
  source: "PAY" | "ADMIN";
  outTradeNo: string | null;
  adminName: string | null;
  note: string | null;
  expiresBefore: string | null;
  expiresAfter: string;
  createdAt: string;
}

/** 会员变动记录列表的一行：用户名、昵称加记录 */
export interface MemberLogRow {
  username: string | null;
  nickname: string | null;
  log: MemberLog;
}

/** 某个用户的会员状态、今天的对话次数和会员记录 */
export interface MemberStatus {
  active: boolean;
  expiresAt?: string | null;
  todayUsed: number;
  dailyLimit: number;
  logs: MemberLog[];
}

/** 账单概况：本月实收（已付款减已退款）、本月付费订单数、有效会员数、累计实收 */
export interface BillingStats {
  monthIncome: number;
  monthPaidOrders: number;
  activeMembers: number;
  totalIncome: number;
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
  /** 是否开放注册、发信邮箱配好没有 */
  registerSetting: () => request<{ open: boolean; mailReady: boolean }>("GET", "/users/register"),
  setRegisterOpen: (open: boolean) => request<{ open: boolean; mailReady: boolean }>("PUT", "/users/register", { open }),
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
  /** 从提供方拉型号列表（OpenAI 兼容的 /models），拉不到时报错，页面上改成手填 */
  llmRemoteModels: (code: string) => request<string[]>("GET", `/llm/providers/${code}/remote-models`),
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

  payOrders: (limit = 100) => request<OrderRow[]>("GET", `/pay/orders?limit=${limit}`),
  queryPayOrder: (no: string) => request<{ state: string; message?: string | null; order: PayOrder }>("POST", `/pay/orders/${no}/query`),
  refundPayOrder: (no: string, amount: string, reason?: string) => request<PayRefund>("POST", `/pay/orders/${no}/refunds`, { amount, reason }),
  retryRefund: (no: string) => request<PayRefund>("POST", `/pay/refunds/${no}/retry`),
  queryRefund: (no: string) => request<PayRefund>("POST", `/pay/refunds/${no}/query`),
  memberSetting: () => request<MemberSetting>("GET", "/pay/member"),
  setMemberPrice: (price: string) => request<MemberSetting>("PUT", "/pay/member/price", { price }),
  memberStatus: (userId: number) => request<MemberStatus>("GET", `/pay/members/${userId}`),
  grantMember: (userId: number, days: number, note?: string) => request<MemberStatus>("POST", `/pay/members/${userId}/grant`, { days, note }),
  setChatQuota: (freeDaily: number, memberDaily: number) => request<MemberSetting>("PUT", "/pay/member/quota", { freeDaily, memberDaily }),
  billingStats: () => request<BillingStats>("GET", "/pay/stats"),
  memberLogs: (limit = 100) => request<MemberLogRow[]>("GET", `/pay/member-logs?limit=${limit}`),
};
