import { request } from "./http";
import type {
  BookingState, ChangeReason, ChatMessage, Conversation, DayPreview, FlightPick, FlightPreview, FlightRules, FlightSearch, FoodResult, DayTickets, HotelComparison, HotelSearchResult, SpotRules, TicketProduct, TuniuTicket, TicketSpot, TuniuCabin, StayPick, StayPreview, Meal, MealPreview, MemoryCategory, MemoryItem, OptimizeGoal, Place, PlacePick,
  ScheduleEvent, TokenView,
  TripDetail, TripVersion, Trip, UserView,
  WeatherBoard, TripForecast, SwapPreview,
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
  copyTrip: (id: number, start?: string) => request<TripDetail>("POST", `/api/v1/trips/${id}/copy` + qs({ start })),
  restoreVersion: (id: number, version: number) =>
    request<TripDetail>("POST", `/api/v1/trips/${id}/versions/${version}/restore`),
  setBooking: (id: number, targetId: string, status: BookingState) =>
    request<TripDetail>("POST", `/api/v1/trips/${id}/booking/${targetId}` + qs({ status })),
  setDone: (id: number, itemId: string, done: boolean) =>
    request<TripDetail>("POST", `/api/v1/trips/${id}/done/${itemId}?done=${done}`),
  tripGeo: (id: number) => request<TripDetail>("POST", `/api/v1/trips/${id}/geo`),
  alternatives: (id: number, itemId: string, keyword?: string) =>
    request<Place[]>("GET", `/api/v1/trips/${id}/items/${itemId}/alternatives` + qs({ keyword })),
  replaceItem: (id: number, itemId: string, place: PlacePick, reason?: ChangeReason | null) =>
    request<TripDetail>("POST", `/api/v1/trips/${id}/items/${itemId}/replace`, { place, reason: reason || null }),
  removeItem: (id: number, itemId: string, reason?: ChangeReason | null) =>
    request<TripDetail>("POST", `/api/v1/trips/${id}/items/${itemId}/remove`, { reason: reason || null }),
  lockItem: (id: number, itemId: string, locked: boolean) =>
    request<TripDetail>("POST", `/api/v1/trips/${id}/items/${itemId}/lock?locked=${locked}`),
  markBooked: (id: number, targetId: string, booked: boolean) =>
    request<TripDetail>("POST", `/api/v1/trips/${id}/booked/${targetId}?booked=${booked}`),
  previewReplace: (id: number, itemId: string, place: PlacePick) =>
    request<DayPreview>("POST", `/api/v1/trips/${id}/items/${itemId}/replace/preview`, place),
  previewOptimize: (id: number, day: number, goal: OptimizeGoal) =>
    request<DayPreview>("GET", `/api/v1/trips/${id}/days/${day}/optimize` + qs({ goal })),
  optimizeDay: (id: number, day: number, goal: OptimizeGoal, ids: string[]) =>
    request<TripDetail>("POST", `/api/v1/trips/${id}/days/${day}/optimize` + qs({ goal }), { ids }),
  reorderDay: (id: number, day: number, ids: string[]) =>
    request<TripDetail>("PUT", `/api/v1/trips/${id}/days/${day}/order`, { ids }),
  switchStay: (id: number, stayId: string, name: string) =>
    request<TripDetail>("POST", `/api/v1/trips/${id}/stays/${stayId}/switch`, { name }),
  requoteStay: (id: number, stayId: string) =>
    request<TripDetail>("POST", `/api/v1/trips/${id}/stays/${stayId}/requote`),
  tripFood: (id: number, day: number, q: { meal: Meal; keyword?: string; maxCost?: number; radius?: number; noSpicy?: boolean; local?: boolean; near?: string }) =>
    request<FoodResult>("GET", `/api/v1/trips/${id}/days/${day}/food` + qs(q)),
  askFood: (id: number, day: number, meal: Meal, text: string) =>
    request<FoodResult>("POST", `/api/v1/trips/${id}/days/${day}/food/ask` + qs({ meal }), { text }),
  previewMeal: (id: number, day: number, meal: Meal, place: PlacePick) =>
    request<MealPreview>("POST", `/api/v1/trips/${id}/days/${day}/meals/${meal}/preview`, place),
  saveMeal: (id: number, day: number, meal: Meal, place: PlacePick, replaces: string | null) =>
    request<TripDetail>("POST", `/api/v1/trips/${id}/days/${day}/meals/${meal}` + qs({ replaces: replaces || undefined }), place),
  addItem: (id: number, day: number, place: PlacePick) =>
    request<TripDetail>("POST", `/api/v1/trips/${id}/days/${day}/items`, place),

  resolveLocation: (lng: number, lat: number) =>
    request<{ lng: number; lat: number; city: string; district: string | null; address: string | null }>(
      "GET", "/api/v1/location/resolve" + qs({ lng, lat })),
  places: (p: { kind?: "spot" | "food" | "hotel"; city?: string; keyword?: string; lng?: number; lat?: number; radius?: number; size?: number }) =>
    request<Place[]>("GET", "/api/v1/places" + qs(p)),
  searchFlights: (origin: string, destination: string, date: string, cabin?: string, refresh?: boolean) =>
    request<FlightSearch>("GET", "/api/v1/search/flights" + qs({ origin, destination, date, cabin, refresh: refresh || undefined })),
  tuniuCabins: (origin: string, destination: string, date: string, flightNo: string, refresh?: boolean) =>
    request<TuniuCabin[]>("GET", "/api/v1/search/flights/tuniu-cabins" + qs({ origin, destination, date, flightNo, refresh: refresh || undefined })),
  flightCalendar: (origin: string, destination: string, date: string, refresh?: boolean) =>
    request<{ date: string; price: number | null; flightNo: string | null; depTime: string | null }[]>("GET", "/api/v1/search/flights/calendar" + qs({ origin, destination, date, refresh: refresh || undefined })),
  hotelCandidates: (q: { city: string; near?: string; checkIn: string; checkOut: string; type?: string; stars?: string; maxPrice?: number; radius?: number; refresh?: boolean }) =>
    request<HotelSearchResult>("GET", "/api/v1/search/hotels/candidates" + qs(q)),
  hotelQuote: (name: string, city: string, checkIn: string, checkOut: string, refresh?: boolean) =>
    request<HotelComparison>("GET", "/api/v1/search/hotels/quote" + qs({ name, city, checkIn, checkOut, refresh: refresh || undefined })),
  previewStay: (id: number, stayId: string | null, hotel: StayPick) =>
    request<StayPreview>("POST", `/api/v1/trips/${id}/stays/preview` + qs({ stayId: stayId || undefined }), hotel),
  applyStay: (id: number, stayId: string | null, hotel: StayPick, replaces: string | null) =>
    request<TripDetail>("POST", `/api/v1/trips/${id}/stays/apply` + qs({ stayId: stayId || undefined, replaces: replaces || undefined }), hotel),
  spots: (city: string, keyword?: string, category?: string, refresh?: boolean) =>
    request<TicketSpot[]>("GET", "/api/v1/search/spots" + qs({ city, keyword, category, refresh: refresh || undefined })),
  spotTickets: (city: string, spot: string, refresh?: boolean) =>
    request<TicketProduct[]>("GET", "/api/v1/search/spots/tickets" + qs({ city, spot, refresh: refresh || undefined })),
  tuniuTickets: (spot: string, date?: string | null, refresh?: boolean) =>
    request<TuniuTicket[]>("GET", "/api/v1/search/spots/tuniu-tickets" + qs({ spot, date: date || undefined, refresh: refresh || undefined })),
  spotRules: (city: string, spot: string, refresh?: boolean) =>
    request<SpotRules>("GET", "/api/v1/search/spots/rules" + qs({ city, spot, refresh: refresh || undefined })),
  tripTickets: (id: number, day: number, refresh?: boolean) =>
    request<DayTickets>("GET", `/api/v1/trips/${id}/days/${day}/tickets` + qs({ refresh: refresh || undefined })),
  mapJsConfig: () => request<{ key: string }>("GET", "/api/v1/maps/js-config"),
  mapPath: (stops: number[][]) =>
    request<number[][][]>("GET", "/api/v1/maps/path" + qs({ stops: stops.map((p) => `${p[0].toFixed(6)},${p[1].toFixed(6)}`).join(";") })),
  flightRules: (flightNo: string, origin: string, destination: string, date: string, refresh?: boolean) =>
    request<FlightRules>("GET", "/api/v1/search/flights/rules" + qs({ flightNo, origin, destination, date, refresh: refresh || undefined })),
  previewFlight: (id: number, day: number, flight: FlightPick) =>
    request<FlightPreview>("POST", `/api/v1/trips/${id}/days/${day}/flight/preview`, flight),
  saveFlight: (id: number, day: number, flight: FlightPick, replaces: string | null) =>
    request<TripDetail>("POST", `/api/v1/trips/${id}/days/${day}/flight` + qs({ replaces: replaces || undefined }), flight),
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

  weatherBoard: (city: string, refresh?: boolean) =>
    request<WeatherBoard>("GET", "/api/v1/weather/board" + qs({ city, refresh: refresh || undefined })),
  tripWeather: (id: number, refresh?: boolean) =>
    request<TripForecast>("GET", `/api/v1/weather/trip/${id}` + qs({ refresh: refresh || undefined })),
  previewSwap: (id: number, a: string, b: string) => request<SwapPreview>("POST", `/api/v1/trips/${id}/swap/preview`, { a, b }),
  saveSwap: (id: number, a: string, b: string, aName: string, bName: string) =>
    request<TripDetail>("POST", `/api/v1/trips/${id}/swap`, { a, b, aName, bName }),
  weatherToday: (city: string) =>
    request<{ city: string; text: string; tempMin: number; tempMax: number }>("GET", `/api/v1/weather/today?city=${encodeURIComponent(city)}`),

  configureBark: (deviceKey: string) => request<void>("PUT", "/api/v1/notify/bark", { deviceKey }),
  testNotify: () => request<void>("POST", "/api/v1/notify/test"),

  tokens: () => request<TokenView[]>("GET", "/api/v1/tokens"),
  createToken: (name: string, scope: string) => request<TokenView>("POST", "/api/v1/tokens", { name, scope }),
  revokeToken: (id: number) => request<void>("DELETE", `/api/v1/tokens/${id}`),
};
