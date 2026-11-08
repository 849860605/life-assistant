<template>
  <view class="ua" :style="{ width: size + 'px', height: size + 'px' }">
    <image class="ua-star" :src="STAR" :style="{ width: Math.round(size * 0.62) + 'px', height: Math.round(size * 0.62) + 'px' }" />
  </view>
</template>

<script setup lang="ts">
/**
 * 用户默认头像：主题色圆形里放白色的星络四角星（用户还不能上传头像）。
 * 和星络自己的头像（BrandMark：浅色方块 + 蓝色星）区分开，对话里一眼能看出谁是谁。
 */
withDefaults(defineProps<{ size?: number }>(), { size: 28 });

const STAR = "data:image/svg+xml;utf8," + encodeURIComponent(
  "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 48 48'><path fill='#fff' d='M21 11C23.72 24.28 23.72 24.28 37 27C23.72 29.72 23.72 29.72 21 43C18.28 29.72 18.28 29.72 5 27C18.28 24.28 18.28 24.28 21 11Z'/>"
  + "<path fill='#fff' fill-opacity='.8' d='M36.5 5C37.6 10.4 37.6 10.4 43 11.5C37.6 12.6 37.6 12.6 36.5 18C35.4 12.6 35.4 12.6 30 11.5C35.4 10.4 35.4 10.4 36.5 5Z'/></svg>",
);
</script>

<style scoped>
.ua { flex-shrink: 0; border-radius: 50%; display: flex; align-items: center; justify-content: center; background: linear-gradient(135deg, rgba(255, 255, 255, 0.28), rgba(0, 0, 0, 0.12)), var(--accent); }
.ua-star { display: block; }
</style>
