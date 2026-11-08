import { reactive } from "vue";

/** 外观：浅色、星光紫（浅色底上的淡紫渐变 + 紫色主色）、深色、跟随系统（浅色或深色） */
export type Appearance = "light" | "aurora" | "dark" | "system";

const APPEARANCE_KEY = "theme:appearance";
/** 以前"背景"单独一项，选过星光紫的换成外观里的星光紫 */
const OLD_BACKDROP_KEY = "theme:backdrop";

/** 当前外观设置；dark 是算出来的实际结果（跟随系统时看系统） */
export const theme = reactive({ appearance: "system" as Appearance, dark: false });

let media: MediaQueryList | null = null;

/** 启动时读本机保存的设置并应用；跟随系统时系统切换深浅色会跟着变 */
export function initTheme() {
  const a = uni.getStorageSync(APPEARANCE_KEY) as Appearance;
  if (a === "light" || a === "aurora" || a === "dark" || a === "system") theme.appearance = a;
  if (uni.getStorageSync(OLD_BACKDROP_KEY)) {
    if (uni.getStorageSync(OLD_BACKDROP_KEY) === "aurora" && theme.appearance !== "dark") setAppearance("aurora");
    uni.removeStorageSync(OLD_BACKDROP_KEY);
  }
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

/** 把结果写到 <html> 上：data-theme 决定深浅色，data-bg 为 aurora 时是星光紫（样式见 App.vue） */
function apply() {
  theme.dark = theme.appearance === "dark" || (theme.appearance === "system" && !!media?.matches);
  if (typeof document === "undefined") return;
  const el = document.documentElement;
  el.dataset.theme = theme.dark ? "dark" : "light";
  el.dataset.bg = theme.appearance === "aurora" ? "aurora" : "plain";
  el.style.colorScheme = theme.dark ? "dark" : "light";
}
