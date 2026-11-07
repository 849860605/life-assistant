import type { Attached } from "./attach";

/**
 * 首页输入框交给对话页：问题和已识别的附件放进本地存储，对话页打开后取走并发出（刷新也不丢）。
 * 草稿：输入框里没发出去的字按位置存一份，发送失败、刷新、去登录再回来都能恢复。
 */
const HANDOFF_KEY = "chat:handoff";
const DRAFT_PREFIX = "draft:";

export interface Handoff {
  text: string;
  files: Attached[];
}

export function setHandoff(h: Handoff) {
  uni.setStorageSync(HANDOFF_KEY, JSON.stringify(h));
}

/** 取走待发的问题，取一次就清掉 */
export function takeHandoff(): Handoff | null {
  const raw = uni.getStorageSync(HANDOFF_KEY);
  if (!raw) return null;
  uni.removeStorageSync(HANDOFF_KEY);
  try {
    return JSON.parse(raw) as Handoff;
  } catch {
    return null;
  }
}

export function loadDraft(key: string): string {
  return (uni.getStorageSync(DRAFT_PREFIX + key) as string) || "";
}

export function saveDraft(key: string, text: string) {
  if (text) uni.setStorageSync(DRAFT_PREFIX + key, text);
  else uni.removeStorageSync(DRAFT_PREFIX + key);
}
