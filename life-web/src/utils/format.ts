import DOMPurify from "dompurify";
import { marked } from "marked";

marked.setOptions({ gfm: true, breaks: true });

// 回答里的链接一律新开窗口，不要把单页应用跳走
DOMPurify.addHook("afterSanitizeAttributes", (node) => {
  if (node.tagName === "A") {
    node.setAttribute("target", "_blank");
    node.setAttribute("rel", "noopener noreferrer");
  }
});

/** Markdown 转成安全的 HTML：模型回答里可能带网页内容，必须过一遍 DOMPurify。 */
export function renderMarkdown(text: string): string {
  return DOMPurify.sanitize(marked.parse(text || "", { async: false }) as string);
}

const pad = (n: number) => String(n).padStart(2, "0");

/**
 * 日期和时间一律按北京时间（账号默认时区 Asia/Shanghai，+8，没有夏令时）显示和计算，
 * 不跟着电脑或手机的时区走：行程、日程都是国内的时间，电脑时区设成别处时也不能显示错。
 */
const ZONE_MINUTES = 480;

/** 换成北京时间的"墙上时间"：返回的 Date 用 getUTC* 读出来就是北京时间的年月日时分。 */
function wall(date: Date): Date {
  return new Date(date.getTime() + ZONE_MINUTES * 60000);
}

/** 北京时间的日期 yyyy-MM-dd。 */
export function ymd(date: Date): string {
  const w = wall(date);
  return `${w.getUTCFullYear()}-${pad(w.getUTCMonth() + 1)}-${pad(w.getUTCDate())}`;
}

/** "yyyy-MM-dd" 往后（负数往前）挪几天，只做日期加减，不受时区影响。 */
export function addDays(date: string, days: number): string {
  const [y, m, d] = date.split("-").map(Number);
  const t = new Date(Date.UTC(y, m - 1, d + days));
  return `${t.getUTCFullYear()}-${pad(t.getUTCMonth() + 1)}-${pad(t.getUTCDate())}`;
}

/** 北京时间 HH:mm。 */
export function hm(date: Date): string {
  const w = wall(date);
  return `${pad(w.getUTCHours())}:${pad(w.getUTCMinutes())}`;
}

/** 北京时间的月、日、星期几（0 是周日）。 */
export function mdw(date: Date): { month: number; day: number; week: number } {
  const w = wall(date);
  return { month: w.getUTCMonth() + 1, day: w.getUTCDate(), week: w.getUTCDay() };
}

/** 北京时间的日期和时间拼成 ISO 字符串，例如 2026-11-07T04:00:00+08:00。 */
export function toOffsetIso(dateText: string, timeText: string): string {
  return `${dateText}T${timeText}:00+08:00`;
}

const WEEK = ["周日", "周一", "周二", "周三", "周四", "周五", "周六"];

/** 日程分组标题：今天、明天，其他显示"11月7日 周六"。 */
export function dayLabel(date: Date): string {
  const diff = Math.round((Date.parse(ymd(date)) - Date.parse(ymd(new Date()))) / 86400000);
  const d = mdw(date);
  const base = `${d.month}月${d.day}日 ${WEEK[d.week]}`;
  if (diff === 0) return `今天 · ${base}`;
  if (diff === 1) return `明天 · ${base}`;
  return base;
}

/** 列表里的相对时间：刚刚、5 分钟前、今天 14:30、10月27日。 */
export function relativeTime(iso: string): string {
  const date = new Date(iso);
  const minutes = Math.floor((Date.now() - date.getTime()) / 60000);
  if (minutes < 1) return "刚刚";
  if (minutes < 60) return `${minutes} 分钟前`;
  if (ymd(date) === ymd(new Date())) return `今天 ${hm(date)}`;
  const d = mdw(date);
  return `${d.month}月${d.day}日`;
}

/** 技能 id 对应的中文名，历史会话没有 route 事件时用它显示。 */
export const SKILL_NAMES: Record<string, string> = {
  travel: "出游",
  schedule: "日程",
  food: "美食",
  general: "通用",
};

/** 打开外部链接（点评、预订页、高德地图）：网页里新开标签页；其他端复制链接让用户自己打开。 */
export function openUrl(url?: string | null) {
  // 只开 http(s) 链接；链接可能来自模型输出，javascript: 之类的一律不开
  if (!url || !/^https?:\/\//i.test(url)) return;
  // #ifdef H5
  window.open(url, "_blank", "noopener");
  // #endif
  // #ifndef H5
  uni.setClipboardData({ data: url, success: () => uni.showToast({ title: "链接已复制", icon: "none" }) });
  // #endif
}

/** "2026-03-10" 显示成"3/10"。 */
export function shortDate(date?: string | null): string {
  if (!date || date.length < 10) return date || "";
  return `${Number(date.slice(5, 7))}/${Number(date.slice(8, 10))}`;
}

/** "2026-03-10" 显示成"周二"。 */
export function weekday(date?: string | null): string {
  if (!date) return "";
  const d = new Date(`${date}T00:00:00`);
  return Number.isNaN(d.getTime()) ? "" : "周" + "日一二三四五六"[d.getDay()];
}

/** 卡片上的查询时间：今天的只显示"10:32"，更早的显示"10/29 10:32"。旧数据是"HH:mm"字符串，原样返回。 */
/** 查询时间写成"刚刚""12 分钟前""3 小时前"，超过一天写日期时间。 */
export function ago(value?: string | null): string {
  if (!value) return "";
  const t = new Date(value).getTime();
  if (Number.isNaN(t)) return value;
  const min = Math.floor((Date.now() - t) / 60000);
  if (min < 1) return "刚刚";
  if (min < 60) return `${min} 分钟前`;
  if (min < 24 * 60) return `${Math.floor(min / 60)} 小时前`;
  return queriedAt(value);
}

export function queriedAt(value?: string | null): string {
  if (!value) return "";
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return value;
  const sameDay = ymd(d) === ymd(new Date());
  const w = mdw(d);
  return (sameDay ? "" : `${w.month}/${w.day} `) + hm(d);
}

/** 能标记"去过了 / 吃过了"的安排：景点和吃饭 */
export function doneable(type: string) {
  return type === "SPOT" || type === "FOOD";
}

/** 这次行程里"已去 / 已吃"的文字：done 为 false 时是按钮上的"标记…"（和规划时的"以前去过，换一个"区分开） */
export function doneText(type: string, done: boolean) {
  const word = type === "FOOD" ? "已吃" : "已去";
  return done ? word : "标记" + word;
}
