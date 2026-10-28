import { request } from "./http";
import type {
  ChatMessage, Conversation, MemoryCategory, MemoryItem, ScheduleEvent, TokenView, TripDetail, TripVersion,
  Trip, UserView,
} from "./types";

/** 所有后端接口，路径和后端 /api/v1 一一对应。 */
export const api = {
  login: (username: string, password: string) =>
    request<{ token: string; user: UserView }>("POST", "/api/v1/auth/login", { username, password, device: "web" }),
  logout: () => request<void>("POST", "/api/v1/auth/logout"),
  me: () => request<UserView>("GET", "/api/v1/auth/me"),

  conversations: () => request<Conversation[]>("GET", "/api/v1/conversations?limit=50"),
  createConversation: () => request<Conversation>("POST", "/api/v1/conversations"),
  deleteConversation: (id: string) => request<void>("DELETE", `/api/v1/conversations/${id}`),
  messages: (id: string) => request<ChatMessage[]>("GET", `/api/v1/conversations/${id}/messages?limit=200`),

  trips: () => request<Trip[]>("GET", "/api/v1/trips"),
  trip: (id: number, version?: number) =>
    request<TripDetail>("GET", `/api/v1/trips/${id}` + (version ? `?version=${version}` : "")),
  tripVersions: (id: number) => request<TripVersion[]>("GET", `/api/v1/trips/${id}/versions`),

  events: (from: string, to: string) => request<ScheduleEvent[]>("GET", `/api/v1/events?from=${from}&to=${to}`),
  createEvent: (body: { title: string; startAt: string; location?: string; remindMinutesBefore?: number | null }) =>
    request<ScheduleEvent>("POST", "/api/v1/events", body),
  deleteEvent: (id: number) => request<void>("DELETE", `/api/v1/events/${id}`),

  memories: () => request<MemoryItem[]>("GET", "/api/v1/memories"),
  saveMemory: (body: { category: MemoryCategory; key: string; content: string }) =>
    request<void>("PUT", "/api/v1/memories", body),
  deleteMemory: (id: number) => request<void>("DELETE", `/api/v1/memories/${id}`),

  configureBark: (deviceKey: string) => request<void>("PUT", "/api/v1/notify/bark", { deviceKey }),
  testNotify: () => request<void>("POST", "/api/v1/notify/test"),

  tokens: () => request<TokenView[]>("GET", "/api/v1/tokens"),
  createToken: (name: string, scope: string) => request<TokenView>("POST", "/api/v1/tokens", { name, scope }),
  revokeToken: (id: number) => request<void>("DELETE", `/api/v1/tokens/${id}`),
};
