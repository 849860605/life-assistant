import { request } from "./http";
import type {
  ChangeReason, ChatMessage, Conversation, MemoryCategory, MemoryItem, Place, PlacePick, ScheduleEvent, TokenView,
  TripDetail, TripVersion, Trip, UserView,
} from "./types";

/** 拼查询串，跳过空值。 */
function qs(params: Record<string, string | number | boolean | null | undefined>): string {
  const parts = Object.entries(params)
    .filter(([, v]) => v !== null && v !== undefined && v !== "")
    .map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`);
  return parts.length ? "?" + parts.join("&") : "";
}

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
  tripEvents: (id: number) => request<ScheduleEvent[]>("GET", `/api/v1/trips/${id}/events`),
  confirmTrip: (id: number) => request<Trip>("POST", `/api/v1/trips/${id}/confirm`),
  deleteTrip: (id: number) => request<void>("DELETE", `/api/v1/trips/${id}`),
  tripGeo: (id: number) => request<TripDetail>("POST", `/api/v1/trips/${id}/geo`),
  alternatives: (id: number, itemId: string, keyword?: string) =>
    request<Place[]>("GET", `/api/v1/trips/${id}/items/${itemId}/alternatives` + qs({ keyword })),
  replaceItem: (id: number, itemId: string, place: PlacePick, reason?: ChangeReason | null) =>
    request<TripDetail>("POST", `/api/v1/trips/${id}/items/${itemId}/replace`, { place, reason: reason || null }),
  removeItem: (id: number, itemId: string, reason?: ChangeReason | null) =>
    request<TripDetail>("POST", `/api/v1/trips/${id}/items/${itemId}/remove`, { reason: reason || null }),
  lockItem: (id: number, itemId: string, locked: boolean) =>
    request<TripDetail>("POST", `/api/v1/trips/${id}/items/${itemId}/lock?locked=${locked}`),
  reorderDay: (id: number, day: number, ids: string[]) =>
    request<TripDetail>("PUT", `/api/v1/trips/${id}/days/${day}/order`, { ids }),
  switchStay: (id: number, stayId: string, name: string) =>
    request<TripDetail>("POST", `/api/v1/trips/${id}/stays/${stayId}/switch`, { name }),
  requoteStay: (id: number, stayId: string) =>
    request<TripDetail>("POST", `/api/v1/trips/${id}/stays/${stayId}/requote`),
  addItem: (id: number, day: number, place: PlacePick) =>
    request<TripDetail>("POST", `/api/v1/trips/${id}/days/${day}/items`, place),

  places: (p: { kind?: "spot" | "food" | "hotel"; city?: string; keyword?: string; lng?: number; lat?: number; radius?: number; size?: number }) =>
    request<Place[]>("GET", "/api/v1/places" + qs(p)),
  searchFlights: (origin: string, destination: string, date: string) =>
    request<Record<string, any>>("GET", "/api/v1/search/flights" + qs({ origin, destination, date })),
  searchHotels: (p: { city: string; place?: string; checkIn: string; checkOut: string; maxPrice?: number }) =>
    request<Record<string, any>>("GET", "/api/v1/search/hotels" + qs(p)),
  searchRestaurants: (p: { city: string; near?: string; cuisine?: string; maxPrice?: number }) =>
    request<Record<string, any>>("GET", "/api/v1/search/restaurants" + qs(p)),

  events: (from: string, to: string) => request<ScheduleEvent[]>("GET", `/api/v1/events?from=${from}&to=${to}`),
  createEvent: (body: { title: string; startAt: string; location?: string; remindMinutesBefore?: number | null }) =>
    request<ScheduleEvent>("POST", "/api/v1/events", body),
  deleteEvent: (id: number) => request<void>("DELETE", `/api/v1/events/${id}`),

  memories: () => request<MemoryItem[]>("GET", "/api/v1/memories"),
  saveMemory: (body: { category: MemoryCategory; key: string; content: string }) =>
    request<void>("PUT", "/api/v1/memories", body),
  deleteMemory: (id: number) => request<void>("DELETE", `/api/v1/memories/${id}`),

  weatherToday: (city: string) =>
    request<{ city: string; text: string; tempMin: number; tempMax: number }>("GET", `/api/v1/weather/today?city=${encodeURIComponent(city)}`),

  configureBark: (deviceKey: string) => request<void>("PUT", "/api/v1/notify/bark", { deviceKey }),
  testNotify: () => request<void>("POST", "/api/v1/notify/test"),

  tokens: () => request<TokenView[]>("GET", "/api/v1/tokens"),
  createToken: (name: string, scope: string) => request<TokenView>("POST", "/api/v1/tokens", { name, scope }),
  revokeToken: (id: number) => request<void>("DELETE", `/api/v1/tokens/${id}`),
};
