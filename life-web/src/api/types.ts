/** 后端统一返回结构，code 为 0 表示成功。 */
export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
}

export interface UserView {
  id: number;
  username: string;
  nickname: string;
  timezone: string;
}

export interface Conversation {
  id: string;
  title: string | null;
  skillId: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ChatMessage {
  id: number;
  role: "USER" | "ASSISTANT" | "SYSTEM" | "TOOL";
  content: string;
  /** JSON 字符串，助手消息上可能有 {"cards": [...]} */
  metadata: string | null;
  createdAt: string;
}

/** 工具结果卡片：kind 决定用哪个组件；offset 是卡片出现时回答已输出的字数。 */
export interface Card {
  kind: "restaurants" | "flights" | "hotels" | "trip" | "requirement" | string;
  data: any;
  summary?: string;
  offset?: number;
}

/** 对话流里的一个事件：route 选中技能，delta 增量文字，card 卡片，done 结束，error 出错。 */
export interface ChatEvent {
  type: "route" | "delta" | "card" | "done" | "error";
  skillId?: string;
  skillName?: string;
  text?: string;
  message?: string;
  card?: Card;
}

export interface Trip {
  id: number;
  title: string;
  tripType: "CITY" | "HIKING" | "MIXED";
  destination: string | null;
  startDate: string | null;
  endDate: string | null;
  currentVer: number;
  status: "DRAFT" | "CONFIRMED" | "FINISHED";
  updatedAt: string;
}

/** 行程里的一项安排；type 是 SPOT / FOOD / HOTEL / TRANSPORT / FREE。 */
export interface ItineraryItem {
  id: string;
  start?: string;
  end?: string;
  type: string;
  name: string;
  address?: string;
  note?: string;
  cost?: number;
  url?: string;
  locked?: boolean;
}

export interface ItineraryDay {
  date: string;
  theme?: string;
  items: ItineraryItem[];
}

export interface Itinerary {
  days: ItineraryDay[];
  tips?: string;
}

export interface TripVersion {
  tripId: number;
  version: number;
  content?: string;
  itinerary?: Itinerary | null;
  changeNote: string | null;
  createdBy: "USER" | "AI";
  createdAt: string;
}

export interface TripDetail {
  trip: Trip;
  version: TripVersion;
}

export interface ScheduleEvent {
  id: number;
  title: string;
  description: string | null;
  location: string | null;
  startAt: string;
  endAt: string | null;
  allDay: boolean;
  source: string;
}

export type MemoryCategory = "PROFILE" | "PREFERENCE" | "RELATION" | "HEALTH" | "FINANCE";

export interface MemoryItem {
  id: number;
  category: MemoryCategory;
  memKey: string;
  content: string;
  source: string;
  sensitive: boolean;
  expireAt: string | null;
  updatedAt: string;
}

export interface TokenView {
  id: number;
  name: string;
  scope: string;
  prefix: string;
  plainKey: string | null;
}
