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
  /** 以下由后端按名称在高德补上 */
  poiId?: string;
  lng?: number;
  lat?: number;
  photo?: string;
  rating?: number;
}

/** 相邻两项之间的路程：WALK 步行、DRIVE 打车/自驾 */
export interface ItineraryLeg {
  from: string;
  to: string;
  mode: "WALK" | "DRIVE";
  minutes: number;
  km: number;
}

export interface ItineraryDay {
  date: string;
  theme?: string;
  items: ItineraryItem[];
  legs?: ItineraryLeg[];
}

/** 一个平台的酒店报价（每晚） */
export interface StayQuote {
  platform: string;
  price: number;
  url?: string;
  basis?: string;
  matchedName?: string;
}

/** 比价时的另一家候选酒店 */
export interface StayOption {
  name: string;
  price?: number;
  platform?: string;
  url?: string;
  quotes?: StayQuote[];
}

/** 一段住宿：checkIn 当晚入住，checkOut 当天离店 */
export interface Stay {
  id: string;
  name: string;
  checkIn: string;
  checkOut: string;
  address?: string;
  /** 每晚最低价和所在平台 */
  price?: number;
  platform?: string;
  url?: string;
  queriedAt?: string;
  quotes?: StayQuote[];
  options?: StayOption[];
  poiId?: string;
  lng?: number;
  lat?: number;
  photo?: string;
  rating?: number;
}

export interface Itinerary {
  days: ItineraryDay[];
  stays?: Stay[];
  /** 用户标记"已预约"的安排和住宿的 id */
  booked?: string[];
  tips?: string;
  /** 已经补过坐标和路程 */
  geo?: boolean;
}

/** 高德地点 */
export interface Place {
  id: string;
  name: string;
  category: string;
  address: string | null;
  area: string | null;
  lng: number | null;
  lat: number | null;
  rating: number | null;
  cost: number | null;
  photo: string | null;
  photos: string[];
  tel: string | null;
  distance: number | null;
}

/** 在行程里换成或加入的地点 */
export interface PlacePick {
  name: string;
  poiId?: string | null;
  address?: string | null;
  lng?: number | null;
  lat?: number | null;
  photo?: string | null;
  rating?: number | null;
  cost?: number | null;
  type?: string;
  note?: string;
}

export type ChangeReason = "VISITED" | "DISLIKE";

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
