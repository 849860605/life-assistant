<template>
  <view class="icon" :style="style" />
</template>

<script setup lang="ts">
import { computed } from "vue";
import { icon } from "../utils/icons";

/**
 * 线性图标：name 见 utils/icons.ts。用 CSS mask 着色，color 可以写 CSS 变量（如 var(--accent)），
 * 不写就跟随文字颜色，深色模式下自动变色。
 */
const props = withDefaults(defineProps<{ name: string; size?: number; color?: string; fill?: boolean }>(), {
  size: 18,
  color: "currentColor",
  fill: false,
});
const style = computed(() => {
  const mask = `url("${icon(props.name, "#000", props.fill)}") center / contain no-repeat`;
  return {
    width: props.size + "px",
    height: props.size + "px",
    background: props.color,
    mask,
    "-webkit-mask": mask,
  };
});
</script>

<style scoped>
.icon { display: inline-block; flex-shrink: 0; vertical-align: middle; }
</style>
