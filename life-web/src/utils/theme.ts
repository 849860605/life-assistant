import { reactive } from "vue";

/** 外观：浅色、深色、跟随系统；背景：默认（白底浅灰）、星光紫（淡紫渐变 + 城市天际线） */
export type Appearance = "light" | "dark" | "system";
export type Backdrop = "plain" | "aurora";

const APPEARANCE_KEY = "theme:appearance";
const BACKDROP_KEY = "theme:backdrop";

/** 当前外观设置；dark 是算出来的实际结果（跟随系统时看系统） */
export const theme = reactive({ appearance: "system" as Appearance, backdrop: "plain" as Backdrop, dark: false });

let media: MediaQueryList | null = null;

/** 启动时读本机保存的设置并应用；跟随系统时系统切换深浅色会跟着变 */
export function initTheme() {
  const a = uni.getStorageSync(APPEARANCE_KEY) as Appearance;
  const b = uni.getStorageSync(BACKDROP_KEY) as Backdrop;
  if (a === "light" || a === "dark" || a === "system") theme.appearance = a;
  if (b === "plain" || b === "aurora") theme.backdrop = b;
  if (typeof window !== "undefined" && window.matchMedia) {
    media = window.matchMedia("(prefers-color-scheme: dark)");
    media.addEventListener?.("change", apply);
  }
  apply();
}

export function setAppearance(a: Appearance) {
  theme.appearance = a;
  uni.setStorageSync(APPEARANCE_KEY, a);
  apply();
}

export function setBackdrop(b: Backdrop) {
  theme.backdrop = b;
  uni.setStorageSync(BACKDROP_KEY, b);
  apply();
}

/** 把结果写到 <html> 上：data-theme 决定深浅色，data-bg 决定背景（样式见 App.vue） */
function apply() {
  theme.dark = theme.appearance === "dark" || (theme.appearance === "system" && !!media?.matches);
  if (typeof document === "undefined") return;
  const el = document.documentElement;
  el.dataset.theme = theme.dark ? "dark" : "light";
  el.dataset.bg = theme.backdrop;
  el.style.colorScheme = theme.dark ? "dark" : "light";
}
