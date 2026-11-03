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
  type: "route" | "delta" | "card" | "progress" | "suggestions" | "done" | "error";
  skillId?: string;
  skillName?: string;
  text?: string;
  message?: string;
  card?: Card;
  /** 回答下面的快捷回复 */
  suggestions?: string[];
  /** 工具执行中的进度，同一个 id 的新进度覆盖旧的 */
  progress?: Progress;
}

/** 工具执行中的一条进度（比价查到第几家了），只在回答过程中显示 */
export interface Progress {
  id: string;
  title: string;
  detail?: string;
  done: boolean;
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

/** 一段公交方案（高德，地铁优先）：minutes 为 0 表示查过、没有公交方案；fee 是每人票价 */
export interface LegTransit {
  minutes: number;
  fee?: number | null;
  walkKm?: number | null;
  lines?: string[];
}

/**
 * 相邻两项之间的路程：mode 是所选方式（WALK 步行、TRANSIT 地铁公交、DRIVE 打车），minutes、km 跟着所选方式。
 * 远的路段两种都查了：driveMinutes、driveKm、taxi 是打车的，transit 是地铁公交的，用来显示另一种方式。
 */
export interface ItineraryLeg {
  from: string;
  to: string;
  mode: "WALK" | "TRANSIT" | "DRIVE";
  minutes: number;
  km: number;
  /** 高德估的打车费（元），步行段没有 */
  taxi?: number | null;
  driveMinutes?: number | null;
  driveKm?: number | null;
  transit?: LegTransit | null;
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
  /** 点过预订链接的项（值 OPENED，"已跳转平台"，还不算订好）；已确认的看 booked */
  bookingStatus?: Record<string, string>;
  /** 用户标记"去过了 / 吃过了"的安排 id */
  done?: string[];
  tips?: string;
  /** 已经补过坐标和路程 */
  geo?: boolean;
  /** 整个行程默认怎么走：TRANSIT 地铁公交（空也按它）、DRIVE 打车 */
  transport?: "TRANSIT" | "DRIVE" | null;
  /** 单独改过的路段，键是"起点 id>终点 id" */
  legModes?: Record<string, "TRANSIT" | "DRIVE">;
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
  /** 高德的特色标签，如"夜景""博物馆"，最多 3 个 */
  tags?: string[];
}

/** 一天路上的合计（全部来自高德）：分钟、公里、打车费、步行段数、打车段数、最后一项结束时间、地铁公交段数和票价（每人） */
export interface DayTotals {
  minutes: number;
  km: number;
  taxi: number | null;
  walks: number;
  drives: number;
  endsAt: string | null;
  transits?: number;
  fare?: number | null;
}

/** 改动预览（没保存）：第几天、改动前后的这一天和合计；changed 为 false 表示没必要改 */
export interface DayPreview {
  day: number;
  before: ItineraryDay;
  after: ItineraryDay;
  beforeTotals: DayTotals;
  afterTotals: DayTotals;
  changed: boolean;
  /** 按高德实际路程排下来，吃饭、交通等固定安排一共会晚开始多少分钟（路线优化才有） */
  lateBefore: number;
  lateAfter: number;
}

/** 路线优化目标：时间最短、距离最短、费用最低 */
export type OptimizeGoal = "TIME" | "DISTANCE" | "COST";

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

/** 预订状态：计划预订、已跳转平台（点过链接，不算订好）、已确认订单（用户自己确认） */
export type BookingState = "PLANNED" | "OPENED" | "CONFIRMED";

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

/** 一顿饭：早餐、午餐、晚餐 */
export type Meal = "BREAKFAST" | "LUNCH" | "DINNER";

/** 找餐厅的筛选条件（实际用的，助手回答时是从那句话拆出来的） */
export interface FoodQuery {
  near: string | null;
  meal: Meal;
  keyword: string | null;
  maxCost: number | null;
  radius: number | null;
  noSpicy: boolean;
  local: boolean;
}

/**
 * 一家候选餐厅：detourKm 是改道去这家多走的直线公里数；minutes、mode 是从上一项过去的路上时间（高德，只有助手回答的前几家有）；
 * local 是按"老字号/特色小吃"搜到的；reason 是按这些数据拼的推荐理由（不是 AI 写的）
 */
export interface FoodCandidate {
  place: Place;
  detourKm: number | null;
  minutes: number | null;
  mode: "WALK" | "DRIVE" | null;
  local: boolean;
  reason: string;
}

/** 某天某一顿顺路的餐厅：按哪里找的、这一顿前后的安排、这一顿现在安排的那家 */
export interface FoodResult {
  day: number;
  meal: Meal;
  anchor: string;
  anchorLng: number;
  anchorLat: number;
  prevName: string | null;
  nextName: string | null;
  currentName: string | null;
  query: FoodQuery;
  places: FoodCandidate[];
  /** 助手回答时的一句话说明 */
  summary: string | null;
}

/** 放进某一顿后比原来晚到的一项；fixed 是用户保留或已预约的 */
export interface MealImpact {
  id: string;
  name: string;
  minutes: number;
  fixed: boolean;
}

/** 把餐厅放进某一顿的预览（没保存） */
export interface MealPreview {
  preview: DayPreview;
  meal: Meal;
  itemId: string;
  start: string;
  end: string;
  replacesId: string | null;
  replacesName: string | null;
  prevName: string | null;
  nextName: string | null;
  foodBefore: number;
  foodAfter: number;
  impacts: MealImpact[];
  /** 路程合计没覆盖到、需要自己确认的地方 */
  notes: string[];
}

/** 某航班在一个平台的价格和预订链接 */
export interface PlatformPrice {
  price: number | null;
  url: string | null;
  /** 另付的税费（只有途牛单独给） */
  tax?: number | null;
}

/** 途牛上某航班的一个舱位：condition 是购买条件（如"青年特惠"），limits 是限制说明 */
export interface TuniuCabin {
  price: number | null;
  tax: number | null;
  condition: string | null;
  limits: string[];
  baggage: string | null;
  meal: string | null;
  refund: string | null;
  seats: string | null;
  cabinClass: string | null;
}

/** 三个平台按航班号合并后的一个航班 */
export interface Flight {
  flightNo: string;
  airline: string;
  depTime: string;
  arrTime: string;
  depAirport: string;
  arrAirport: string;
  note: string | null;
  prices: Record<string, PlatformPrice>;
  lowest: number | null;
  lowestPlatform: string | null;
  /** 全程分钟，平台没给为空 */
  minutes: number | null;
  aircraft: string | null;
  transfer: boolean;
  nextDay: boolean;
  /** 机场三字码、中转城市（飞猪给的，别的平台没有为空） */
  depCode: string | null;
  arrCode: string | null;
  via: string | null;
}

/** 机票比价结果：platforms 是各平台状态（"OK，16 个航班" 或失败原因） */
export interface FlightSearch {
  origin: string;
  destination: string;
  date: string;
  platforms: Record<string, string>;
  flights: Flight[];
  queriedAt: string;
  error?: string;
  /** 一班都没查到时的说明（日期太远还没开售、额度用完等） */
  notice?: string;
}

/** 携程问道给的行李额、退改签说明（一般规则，以购票页面为准） */
export interface FlightRules {
  flightNo: string;
  date: string;
  items: Record<string, string>;
  summary: string;
  source: string;
  queriedAt: string;
}

/** 写进行程的航班 */
export interface FlightPick {
  flightNo: string;
  airline: string | null;
  depTime: string;
  arrTime: string;
  depAirport: string | null;
  arrAirport: string | null;
  price: number | null;
  platform: string | null;
  url: string | null;
}

/** 把航班写进某天的预览：换掉原来的航班（replacesId）或新加；conflicts 是时间对不上的安排 */
export interface FlightPreview {
  day: number;
  item: ItineraryItem;
  replacesId: string | null;
  replacesName: string | null;
  replacesTime: string | null;
  conflicts: { id: string; name: string; problem: string; fixed: boolean }[];
  notes: string[];
}

/** 酒店比价页的一家候选（飞猪）：price 是这几天每晚最低价，star 是档次，renovated 是装修年份，distance 是离地标的直线米数 */
export interface HotelOption {
  id: string | null;
  name: string;
  address: string | null;
  lng: number | null;
  lat: number | null;
  photo: string | null;
  price: number | null;
  rating: number | null;
  star: string | null;
  renovated: string | null;
  near: string | null;
  url: string | null;
  distance: number | null;
}

export interface HotelSearchResult {
  city: string;
  near: string | null;
  anchorLng: number | null;
  anchorLat: number | null;
  radius: number;
  checkIn: string;
  checkOut: string;
  hotels: HotelOption[];
  queriedAt: string;
  note: string | null;
}

/** 一个平台对一家酒店的报价：status OK / NO_PRICE / FAILED；basis 是价格口径；matchedName 是平台上实际匹配到的名字 */
export interface HotelPlatformQuote {
  platform: string;
  status: string;
  price: number | null;
  basis: string | null;
  url: string | null;
  matchedName: string | null;
  rooms: string[];
  note: string | null;
}

export interface HotelComparison {
  city: string;
  checkIn: string;
  checkOut: string;
  queriedAt: string;
  hotels: { hotel: string; quotes: HotelPlatformQuote[] }[];
}

/** 换进行程的酒店 */
export interface StayPick {
  name: string;
  address: string | null;
  lng: number | null;
  lat: number | null;
  photo: string | null;
  rating: number | null;
  price: number | null;
  platform: string | null;
  url: string | null;
  quotes: StayQuote[] | null;
}

/** 换酒店的预览：每天路程前后对比（高德） */
export interface StayPreview {
  stay: Stay;
  replacesId: string | null;
  replacesName: string | null;
  oldPrice: number | null;
  nights: number;
  days: { day: number; date: string; before: DayTotals; after: DayTotals }[];
  notes: string[];
}

/** 飞猪上的一个景点：level 是 A 级，rank 是榜单说明，free 为 true 是免费景点（是否要预约另说） */
export interface TicketSpot {
  id: string | null;
  name: string;
  category: string | null;
  address: string | null;
  description: string | null;
  lng: number | null;
  lat: number | null;
  photo: string | null;
  level: number | null;
  rank: string | null;
  free: boolean | null;
  url: string | null;
}

/** 票种：type 是成人、儿童、学生、老人、优待，看不出来为空 */
export interface TicketSku {
  name: string;
  price: number | null;
  type: string | null;
}

/** 门票商品：bundle 是含讲解、一日游、套餐；adult、child 是票种名称里能看出来的成人价、儿童价 */
export interface TicketProduct {
  title: string;
  price: number | null;
  adult: number | null;
  child: number | null;
  bundle: boolean;
  skus: TicketSku[];
  url: string | null;
}

/** 携程问道给的预约规则：reservation 是 NEED 要预约、NO_NEED 不用、UNKNOWN 看不出来 */
export interface SpotRules {
  spot: string;
  items: Record<string, string>;
  summary: string;
  reservation: "NEED" | "NO_NEED" | "UNKNOWN";
  source: string;
  queriedAt: string;
}

/** 行程某天每个景点的门票 */
export interface DayTickets {
  day: number;
  date: string;
  spots: { itemId: string; name: string; start: string | null; spot: TicketSpot | null; free: boolean | null; product: TicketProduct | null; error: string | null }[];
  queriedAt: string;
}

/** 天气页：实况（拿不到为空） */
export interface WeatherNow {
  text: string;
  temp: number | null;
  feelsLike: number | null;
  humidity: number | null;
  windDir: string | null;
  windScale: string | null;
  obsTime: string | null;
}

/** 空气质量（和风才有） */
export interface AirQuality {
  aqi: number | null;
  category: string | null;
  pm2p5: string | null;
  pm10: string | null;
  so2: string | null;
  no2: string | null;
  o3: string | null;
  co: string | null;
}

export interface DailyWeather {
  date: string;
  textDay: string;
  textNight: string;
  tempMin: number;
  tempMax: number;
  windScaleDay: string;
  precipMm: number;
  uvIndex: string;
  sunrise: string;
  sunset: string;
}

export interface HourlyWeather {
  time: string;
  text: string;
  temp: number;
  windScale: string;
  pop: number;
  precipMm: number;
}

/** 天气页数据：missing 是缺了哪些数据和原因；tips 是按天气数据规则算的出行建议 */
export interface WeatherBoard {
  source: string;
  city: string;
  adm: string;
  lon: number;
  lat: number;
  now: WeatherNow | null;
  air: AirQuality | null;
  hourly: HourlyWeather[];
  daily: DailyWeather[];
  warnings: { title: string; level: string; type: string; text: string }[];
  nearby: { name: string; text: string; tempMin: number; tempMax: number }[];
  tips: { kind: string; text: string }[];
  missing: string[];
  queriedAt: string;
}

/** 行程每天的天气和下雨天的对调建议 */
export interface TripForecast {
  tripId: number;
  city: string;
  days: { day: number; date: string; forecast: DailyWeather | null; rainy: boolean }[];
  suggestion: { rainDay: number; outdoorId: string; outdoorName: string; dryDay: number; indoorId: string; indoorName: string; text: string } | null;
  note: string | null;
}

/** 跨天对调两个景点的预览 */
export interface SwapPreview {
  first: DayPreview;
  second: DayPreview;
  impacts: MealImpact[];
  note: string;
}

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

/** 途牛上的一个票种：price 是起价（可售日期里的最低价），没有预订链接 */
export interface TuniuTicket {
  name: string;
  type: string | null;
  category: string | null;
  price: number | null;
  refund: string | null;
  enter: string | null;
  advance: string | null;
  open: string | null;
  saleFrom: string | null;
  saleTo: string | null;
}

/** 上传的文件和识别结果：识别失败时 error 有值 */
export interface UploadView {
  id: string;
  name: string;
  kind: "IMAGE" | "PDF" | "WORD" | "EXCEL" | "TEXT";
  size: number;
  pages?: number | null;
  chars: number;
  preview: string;
  error?: string | null;
}

/** 每日简报的设置：city 为空时 suggestedCity 是从记忆里猜的常住城市 */
export interface BriefSettings {
  enabled: boolean;
  sendTime: string;
  city?: string | null;
  suggestedCity?: string | null;
  hasChannel: boolean;
}

/** 一份每日简报：几段，每段几行字 */
export interface Brief {
  date: string;
  title: string;
  sections: { key: string; title: string; lines: string[]; url?: string }[];
}
