import { onMounted, onUnmounted, ref } from "vue";

/** 宽屏（电脑）的分界：大于等于这个宽度时显示左侧导航栏和多栏布局 */
export const WIDE_MIN = 1024;

function width(): number {
  // #ifdef H5
  return window.innerWidth;
  // #endif
  // #ifndef H5
  return uni.getWindowInfo().windowWidth;
  // #endif
}

/** 当前是不是宽屏，窗口大小变化时跟着变。 */
export function useWide() {
  const wide = ref(width() >= WIDE_MIN);
  const update = () => {
    wide.value = width() >= WIDE_MIN;
  };
  onMounted(() => uni.onWindowResize(update));
  onUnmounted(() => uni.offWindowResize(update));
  return wide;
}
