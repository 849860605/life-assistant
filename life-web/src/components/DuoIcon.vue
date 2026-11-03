<template>
  <view v-if="tile" class="duo-tile" :style="tileStyle">
    <image class="duo-img" :src="src" :style="imgStyle" />
  </view>
  <image v-else class="duo-img" :src="src" :style="imgStyle" />
</template>

<script setup lang="ts">
import { computed } from "vue";
import { duoIcon } from "../utils/duoIcons";

/**
 * 双色填充图标（导航、功能入口用），name 见 utils/duoIcons.ts。size 是图标边长；
 * tile 不为 0 时放在一个浅色圆角方块里，tile 是方块边长，方块颜色跟随深浅色模式。
 */
const props = withDefaults(defineProps<{ name: string; size?: number; tile?: number }>(), { size: 18, tile: 0 });
const src = computed(() => duoIcon(props.name));
const imgStyle = computed(() => ({ width: props.size + "px", height: props.size + "px" }));
const tileStyle = computed(() => ({
  width: props.tile + "px",
  height: props.tile + "px",
  borderRadius: Math.round(props.tile * 0.3) + "px",
}));
</script>

<style scoped>
.duo-tile { flex-shrink: 0; display: flex; align-items: center; justify-content: center; background: var(--tile-bg); box-shadow: var(--tile-shadow); }
.duo-img { flex-shrink: 0; display: block; }
</style>
