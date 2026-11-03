<template>
  <view class="brand-mark" :style="tileStyle">
    <image class="brand-star" :src="src" :style="starStyle" />
  </view>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { duoIcon } from "../utils/duoIcons";

/** 品牌图标：浅色圆角方块里放蓝色渐变的四角星，和侧栏菜单图标同一套风格；侧栏 Logo、登录页和星络头像都用它。size 是边长（像素）。 */
const props = withDefaults(defineProps<{ size?: number }>(), { size: 32 });
const src = duoIcon("spark");
const tileStyle = computed(() => ({
  width: props.size + "px",
  height: props.size + "px",
  borderRadius: Math.round(props.size * 0.3) + "px",
}));
const starStyle = computed(() => {
  const s = Math.round(props.size * 0.64) + "px";
  return { width: s, height: s };
});
</script>

<style scoped>
.brand-mark { flex-shrink: 0; display: flex; align-items: center; justify-content: center; background: var(--tile-bg); box-shadow: var(--brand-shadow); }
.brand-star { display: block; }
</style>
