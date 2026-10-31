/**
 * 原型演示用的示例数据：西安 4 天 3 晚、2 大 1 小。图片是高德 POI 的真实照片，评分、人均来自高德，
 * 路线距离和时长由高德驾车路线算出；航班、酒店价格是示例值。接上真实数据后这些只留作空状态的示例。
 */
import type { ItineraryDay } from "../api/types";

const IMG = "/static/demo/";

/** 地点名关键字 → 图片，真实行程里的地点也按名字匹配这里的图 */
export const PLACE_IMAGES: Record<string, string> = {
  兵马俑: IMG + "xa-bmy.jpg",
  秦始皇: IMG + "xa-bmy.jpg",
  大雁塔: IMG + "xa-dyt.jpg",
  大慈恩寺: IMG + "xa-dyt.jpg",
  城墙: IMG + "xa-cq.jpg",
  永宁门: IMG + "xa-cq.jpg",
  华清: IMG + "xa-hqg.jpg",
  大唐不夜城: IMG + "xa-dtbyc.jpg",
  回民街: IMG + "xa-hmj.jpg",
  钟鼓楼: IMG + "xa-hmj.jpg",
  陕西历史博物馆: IMG + "xa-sbh.jpg",
  碑林: IMG + "xa-beilin.jpg",
  书院门: IMG + "xa-sym.jpg",
  泡馍: IMG + "xa-paomo.jpg",
  肉夹馍: IMG + "xa-rjm.jpg",
  灌汤包: IMG + "xa-gtb.jpg",
  葫芦头: IMG + "xa-hlt.jpg",
  大牌档: IMG + "xa-hlj.jpg",
  亚朵: IMG + "xa-hotel1.jpg",
  // 早先的成都示例
  大熊猫: IMG + "panda.jpg",
  人民公园: IMG + "park.jpg",
  锦里: IMG + "jinli.jpg",
  宽窄巷子: IMG + "kuanzhai.jpg",
  都江堰: IMG + "dujiangyan.jpg",
  火锅: IMG + "hotpot.jpg",
};

/** 目的地 → 封面图 */
export const CITY_COVERS: Record<string, string> = {
  西安: IMG + "xa-cq.jpg",
  成都: IMG + "chengdu.jpg",
  上海: IMG + "shanghai.jpg",
  都江堰: IMG + "dujiangyan.jpg",
  四姑娘山: IMG + "hero.jpg",
};

/** 按名字找图，找不到返回空串（页面显示类型图标占位）。 */
export function imageFor(name?: string | null): string {
  if (!name) return "";
  const key = Object.keys(PLACE_IMAGES).find((k) => name.includes(k));
  return key ? PLACE_IMAGES[key] : "";
}

export function coverFor(destination?: string | null): string {
  if (!destination) return "";
  const key = Object.keys(CITY_COVERS).find((k) => destination.includes(k));
  return key ? CITY_COVERS[key] : "";
}

export const CITY = "西安";
export const HERO = IMG + "xa-hero.jpg";
export const DAY_HERO = IMG + "xa-hmj.jpg";
export const ROUTE_MAP = IMG + "xa-map-d1-tall.png";
export const ROUTE_MAP_WIDE = IMG + "xa-map-d1.png";
export const HOME_MAP = IMG + "xa-map-home.png";
export const NEAR_MAP = IMG + "xa-map-near.png";

/** 需求摘要（对话页右侧、行程编辑页顶部） */
export const REQUIREMENT = {
  dates: "3月10日 – 3月13日（4天3晚）",
  people: "2 位成人 + 1 位儿童",
  budget: "约 ¥8,000",
  budgetNote: "含机票 + 酒店 + 门票 + 餐饮 + 交通",
  tags: ["历史文化", "亲子友好", "美食体验"],
  prefs: "舒适不赶路 · 性价比优先",
};

/** 三套方案（原型③） */
export const PLANS = [
  {
    key: "budget",
    name: "省钱方案",
    badge: "高性价比",
    tagline: "高性价比 · 经典必玩",
    price: 2800,
    tone: "green",
    icon: "checkCircle",
    points: ["经典景点全覆盖", "经济型酒店", "公共交通为主", "适合预算有限的家庭"],
    image: IMG + "xa-bmy.jpg",
  },
  {
    key: "comfort",
    name: "舒适方案",
    badge: "推荐",
    tagline: "品质体验 · 轻松游玩",
    price: 3800,
    tone: "blue",
    icon: "crown",
    points: ["精选景点 + 特色美食", "舒适型酒店", "专车/包车出行", "兼顾性价比与体验"],
    image: IMG + "xa-dyt.jpg",
    recommended: true,
  },
  {
    key: "deep",
    name: "深度体验方案",
    badge: "品质体验",
    tagline: "小众景点 · 文化深度",
    price: 5600,
    tone: "violet",
    icon: "sparkle",
    points: ["小众景点 + 文化体验", "高端酒店", "特色美食体验", "更深度的西安之旅"],
    image: IMG + "xa-cq.jpg",
  },
];

/** 示例行程（原型④、行程编辑页），结构和后端的结构化行程一样；legs 是到下一站的交通 */
export const DEMO_TRIP = {
  id: 0,
  title: "西安 · 4天3晚",
  plan: "舒适方案",
  destination: "西安",
  startDate: "2026-03-10",
  endDate: "2026-03-13",
  status: "DRAFT",
  days: [
    {
      date: "2026-03-10",
      theme: "西安经典一日",
      items: [
        { id: "d1-1", start: "09:00", end: "11:30", type: "SPOT", name: "兵马俑博物馆", note: "世界第八大奇迹，感受秦始皇的壮丽", address: "临潼区秦陵北路", cost: 120 },
        { id: "d1-2", start: "13:00", end: "14:30", type: "SPOT", name: "华清宫", note: "唐代皇家温泉，感受盛唐文化", address: "临潼区华清路38号", cost: 120 },
        { id: "d1-3", start: "15:30", end: "17:30", type: "SPOT", name: "西安城墙（永宁门）", note: "中国现存最完整的古城墙，可以骑行", address: "碑林区南大街", cost: 54 },
        { id: "d1-4", start: "18:30", end: "20:30", type: "SPOT", name: "回民街钟鼓楼", note: "当地特色美食，感受市井烟火气", address: "莲湖区北院门" },
      ],
    },
    {
      date: "2026-03-11",
      theme: "盛唐文化",
      items: [
        { id: "d2-1", start: "09:00", end: "11:00", type: "SPOT", name: "大雁塔 · 大慈恩寺", note: "唐代佛教文化地标", address: "雁塔区雁塔路", cost: 40 },
        { id: "d2-2", start: "13:30", end: "16:30", type: "SPOT", name: "陕西历史博物馆", note: "中华文明宝库，需提前预约", address: "雁塔区小寨东路91号", cost: 0 },
        { id: "d2-3", start: "18:00", end: "21:00", type: "SPOT", name: "大唐不夜城", note: "夜游盛唐，灯光和演出很好看", address: "雁塔区慈恩路" },
      ],
    },
    {
      date: "2026-03-12",
      theme: "碑林 + 书院门",
      items: [
        { id: "d3-1", start: "09:30", end: "11:30", type: "SPOT", name: "碑林博物馆", note: "中国古代碑刻艺术宝库", address: "碑林区三学街15号", cost: 65 },
        { id: "d3-2", start: "11:30", end: "12:30", type: "SPOT", name: "书院门文化街", note: "笔墨纸砚一条街，适合慢逛拍照", address: "碑林区书院门" },
        { id: "d3-3", start: "12:30", end: "13:30", type: "FOOD", name: "樊记腊汁肉夹馍", note: "本地老字号", address: "莲湖区竹笆市", cost: 20 },
      ],
    },
    {
      date: "2026-03-13",
      theme: "城墙骑行 · 返程",
      items: [
        { id: "d4-1", start: "09:00", end: "11:00", type: "SPOT", name: "西安城墙骑行", note: "从永宁门上城墙，租车绕半圈", cost: 54 },
        { id: "d4-2", start: "14:00", end: "16:00", type: "TRANSPORT", name: "前往咸阳机场", note: "机场大巴约 1 小时" },
      ],
    },
  ] as ItineraryDay[],
  tips: "- 陕西历史博物馆要提前 7 天在公众号预约\n- 兵马俑离市区 36 公里，早点出发避开人流\n- 3 月早晚温差大，带件外套",
};

/** 第 1 天各站之间的交通（高德驾车路线） */
export const DAY1_LEGS: Record<string, string> = {
  "d1-1": "乘车 31 分钟（约 15 公里）",
  "d1-2": "乘车 49 分钟（约 38 公里）",
  "d1-3": "乘车 11 分钟（约 3.4 公里）",
};

/** 第 1 天概览（原型行程编辑页中栏） */
export const DAY1_SUMMARY = { spots: 4, foods: 2, hours: "约 10 小时", transport: "约 ¥120/人", distance: "约 56 公里", drive: "约 1.5 小时" };

/** 景点替换候选（原型⑤ / 替换弹窗）：评分来自高德，距离和车程是从回民街出发的估算 */
export const REPLACEMENTS = [
  { name: "大唐不夜城", rating: 4.8, distance: "3.1 公里", drive: "约 15 分钟车程", tags: ["夜景打卡", "文化体验", "演出活动"], desc: "盛唐文化主题街区，夜景和表演非常值得一看。", image: IMG + "xa-dtbyc.jpg" },
  { name: "西安城墙（南门）", rating: 4.8, distance: "3.8 公里", drive: "约 20 分钟车程", tags: ["历史古迹", "骑行体验", "城市地标"], desc: "中国现存最完整的古城墙，可以步行或骑行游览。", image: IMG + "xa-cq.jpg" },
  { name: "大雁塔 · 大慈恩寺", rating: 4.8, distance: "4.5 公里", drive: "约 25 分钟车程", tags: ["历史文化", "佛教文化", "夜景"], desc: "唐代佛教文化地标，夜景和音乐喷泉很受欢迎。", image: IMG + "xa-dyt.jpg" },
  { name: "碑林博物馆", rating: 4.8, distance: "4.2 公里", drive: "约 22 分钟车程", tags: ["历史文化", "书法艺术", "室内景点"], desc: "中国古代碑刻艺术的宝库，适合喜欢历史文化的游客。", image: IMG + "xa-beilin.jpg" },
  { name: "书院门文化街", rating: 4.7, distance: "2.9 公里", drive: "约 15 分钟车程", tags: ["文化街区", "文创购物", "拍照打卡"], desc: "以书画文创为特色的文化街区，适合慢逛和拍照。", image: IMG + "xa-sym.jpg" },
  { name: "陕西历史博物馆", rating: 4.8, distance: "4.1 公里", drive: "约 20 分钟车程", tags: ["博物馆", "亲子友好"], desc: "中华文明宝库，适合带孩子，需要提前预约。", image: IMG + "xa-sbh.jpg" },
];

/** 热门景点（首页）：评分来自高德 */
export const HOT_SPOTS = [
  { name: "兵马俑", rating: 4.7, tags: ["历史遗迹", "世界文化遗产"], image: IMG + "xa-bmy.jpg" },
  { name: "大雁塔", rating: 4.8, tags: ["佛教圣地", "地标建筑"], image: IMG + "xa-dyt.jpg" },
  { name: "西安城墙", rating: 4.8, tags: ["古城风貌", "夜景推荐"], image: IMG + "xa-cq.jpg" },
  { name: "华清宫", rating: 4.7, tags: ["皇家园林", "温泉遗址"], image: IMG + "xa-hqg.jpg" },
  { name: "大唐不夜城", rating: 4.8, tags: ["夜景灯光", "文化演出"], image: IMG + "xa-dtbyc.jpg" },
  { name: "回民街", rating: 4.8, tags: ["美食街区", "特色小吃"], image: IMG + "xa-hmj.jpg" },
];

/** 附近美食：评分、人均来自高德 */
export const FOODS = [
  { name: "樊记腊汁肉夹馍", rating: 4.6, avgPrice: 20, distance: "1.2 公里", area: "回民街", category: "小吃", tags: ["本地特色"], image: IMG + "xa-rjm.jpg" },
  { name: "老米家泡馍", rating: 4.0, avgPrice: 35, distance: "2.1 公里", area: "回民街", category: "陕菜", tags: ["西安必吃"], image: IMG + "xa-paomo.jpg" },
  { name: "贾三灌汤包", rating: 4.6, avgPrice: 37, distance: "800 米", area: "回民街", category: "小吃", tags: ["特色小吃"], image: IMG + "xa-gtb.jpg" },
  { name: "长安大牌档（西市盛景店）", rating: 4.7, avgPrice: 35, distance: "3.5 公里", area: "大唐西市", category: "陕菜", tags: ["陕菜葫芦鸡", "适合家庭"], image: IMG + "xa-hlj.jpg" },
  { name: "西安饭庄（钟楼店）", rating: 4.6, avgPrice: 116, distance: "600 米", area: "钟楼", category: "陕菜", tags: ["非遗老字号"], image: IMG + "xa-hlt.jpg" },
];

/** 机票：示例价格 */
export const FLIGHTS = [
  { airline: "中国南方航空", short: "南航", flightNo: "CZ3209", dep: "08:20", arr: "11:10", depAirport: "深圳宝安T3", arrAirport: "西安咸阳T3", duration: "2h50m", price: 680, platform: "飞猪", color: "#1b4ea3" },
  { airline: "中国东方航空", short: "东航", flightNo: "MU2597", dep: "09:15", arr: "12:05", depAirport: "深圳宝安T3", arrAirport: "西安咸阳T3", duration: "2h50m", price: 720, platform: "携程", color: "#c8161d" },
  { airline: "深圳航空", short: "深航", flightNo: "ZH9211", dep: "13:40", arr: "16:30", depAirport: "深圳宝安T3", arrAirport: "西安咸阳T3", duration: "2h50m", price: 760, platform: "美团", color: "#d7262b" },
  { airline: "海南航空", short: "海航", flightNo: "HU7737", dep: "18:55", arr: "21:50", depAirport: "深圳宝安T3", arrAirport: "西安咸阳T2", duration: "2h55m", price: 810, platform: "飞猪", color: "#e0a100" },
];

/** 酒店：评分来自高德，价格是示例值 */
export const HOTELS = [
  { name: "西安钟鼓楼广场亚朵酒店", rating: 4.6, distance: "距钟楼 300 米", tags: ["位置好", "近地铁", "亲子友好"], price: 420, platform: "RollingGo", image: IMG + "xa-hotel1.jpg" },
  { name: "全季酒店（西安钟楼回民街店）", rating: 4.7, distance: "距钟楼 500 米", tags: ["免费取消", "含早餐"], price: 360, platform: "携程", image: IMG + "xa-hotel3.jpg" },
  { name: "西安索菲特人民大厦", rating: 4.6, distance: "距钟楼 1.5 公里", tags: ["免费取消", "含早餐"], price: 780, platform: "飞猪", image: IMG + "xa-hotel2.jpg" },
  { name: "西安W酒店", rating: 4.7, distance: "距大雁塔 3 公里", tags: ["免费取消"], price: 980, platform: "美团", image: IMG + "xa-hotel4.jpg" },
];

/** 第 1 天路线（地图页） */
export const DAY1_ROUTE = [
  { no: 1, name: "兵马俑博物馆", time: "09:00 - 11:30", next: "约 31 分钟", image: IMG + "xa-bmy.jpg" },
  { no: 2, name: "华清宫", time: "13:00 - 14:30", next: "约 49 分钟", image: IMG + "xa-hqg.jpg" },
  { no: 3, name: "西安城墙", time: "15:30 - 17:30", next: "约 11 分钟", image: IMG + "xa-cq.jpg" },
  { no: 4, name: "回民街钟鼓楼", time: "18:30 - 20:30", image: IMG + "xa-hmj.jpg" },
];

/** 首页地图：市区一日（高德驾车路线） */
export const HOME_ROUTE = { drive: "33 分钟", bike: "约 1 小时", transit: "约 1 小时 10 分" };

/** 四天的行程概要（对话页右侧的地图预览） */
export const DAY_OUTLINE = [
  { day: "第1天", text: "兵马俑 + 华清宫 + 城墙" },
  { day: "第2天", text: "大雁塔 + 陕西历史博物馆" },
  { day: "第3天", text: "碑林 + 书院门" },
  { day: "第4天", text: "城墙骑行 · 返程" },
];

/** 方案亮点（对话页右侧） */
export const HIGHLIGHTS = [
  { name: "兵马俑博物馆", desc: "世界第八大奇迹", image: IMG + "xa-bmy.jpg" },
  { name: "大雁塔", desc: "唐代佛教文化地标", image: IMG + "xa-dyt.jpg" },
  { name: "西安城墙", desc: "现存最完整古城墙", image: IMG + "xa-cq.jpg" },
  { name: "回民街美食", desc: "地道陕西美食", image: IMG + "xa-hmj.jpg" },
  { name: "陕西历史博物馆", desc: "中华文明宝库", image: IMG + "xa-sbh.jpg" },
];

/** AI 调整建议（行程编辑页） */
export const SUGGESTIONS = [
  "第 1 天兵马俑到城墙车程较长，建议上午早点出发",
  "如果对博物馆感兴趣，可以加陕西历史博物馆（需提前预约）",
  "晚上可以选择大唐不夜城，体验夜景和表演",
];

/** 发现页：热门目的地 */
export const DESTINATIONS = [
  { name: "西安", tagline: "十三朝古都的历史和美食", image: IMG + "xa-cq.jpg" },
  { name: "成都", tagline: "熊猫、火锅和慢生活", image: IMG + "chengdu.jpg" },
  { name: "四姑娘山", tagline: "雪山、海子与草甸", image: IMG + "hero.jpg" },
  { name: "上海", tagline: "外滩夜景与城市漫步", image: IMG + "shanghai.jpg" },
];
