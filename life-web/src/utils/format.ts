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

/** 本地日期 yyyy-MM-dd。 */
export function ymd(date: Date): string {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

/** 本地时间 HH:mm。 */
export function hm(date: Date): string {
  return `${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** 本地日期和时间拼成带时区偏移的 ISO 字符串，例如 2026-11-07T04:00:00+08:00。 */
export function toOffsetIso(dateText: string, timeText: string): string {
  const local = new Date(`${dateText}T${timeText}:00`);
  const offset = -local.getTimezoneOffset();
  const sign = offset >= 0 ? "+" : "-";
  const abs = Math.abs(offset);
  return `${dateText}T${timeText}:00${sign}${pad(Math.floor(abs / 60))}:${pad(abs % 60)}`;
}

const WEEK = ["周日", "周一", "周二", "周三", "周四", "周五", "周六"];

/** 日程分组标题：今天、明天，其他显示"11月7日 周六"。 */
export function dayLabel(date: Date): string {
  const today = new Date();
  const diff = Math.round((new Date(ymd(date)).getTime() - new Date(ymd(today)).getTime()) / 86400000);
  const base = `${date.getMonth() + 1}月${date.getDate()}日 ${WEEK[date.getDay()]}`;
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
  return `${date.getMonth() + 1}月${date.getDate()}日`;
}

/** 技能 id 对应的中文名，历史会话没有 route 事件时用它显示。 */
export const SKILL_NAMES: Record<string, string> = {
  travel: "出游",
  schedule: "日程",
  food: "美食",
  general: "通用",
};

/** 打开外部链接（点评、预订页）：网页里新开标签页；其他端复制链接让用户自己打开。 */
export function openUrl(url?: string | null) {
  if (!url) return;
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
export function queriedAt(value?: string | null): string {
  if (!value) return "";
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return value;
  const today = new Date();
  const sameDay = d.toDateString() === today.toDateString();
  return (sameDay ? "" : `${d.getMonth() + 1}/${d.getDate()} `) + hm(d);
}
