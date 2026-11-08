<template>
  <view class="tp">
    <view class="tp-row">
      <view class="tp-label"><Icon name="sparkle" :size="16" color="var(--ink)" /><text>外观</text></view>
      <view class="tp-seg">
        <text v-for="o in APPEARANCES" :key="o.v" class="tp-opt" :class="{ on: theme.appearance === o.v }" @click="setAppearance(o.v)">{{ o.label }}</text>
      </view>
    </view>
    <view class="tp-row">
      <view class="tp-label"><Icon name="layers" :size="16" color="var(--ink)" /><text>背景</text></view>
      <view class="tp-seg">
        <view v-for="o in BACKDROPS" :key="o.v" class="tp-opt" :class="{ on: theme.backdrop === o.v }" @click="setBackdrop(o.v)">
          <view class="tp-swatch" :class="o.v" /><text>{{ o.label }}</text>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { setAppearance, setBackdrop, theme, type Appearance, type Backdrop } from "../utils/theme";
import Icon from "./Icon.vue";

/** 外观设置（账号菜单和"我的"页共用）：浅色 / 深色 / 跟随系统，背景默认 / 星光紫；只存在这台设备上 */
const APPEARANCES: { v: Appearance; label: string }[] = [
  { v: "light", label: "浅色" },
  { v: "dark", label: "深色" },
  { v: "system", label: "跟随系统" },
];
const BACKDROPS: { v: Backdrop; label: string }[] = [
  { v: "plain", label: "默认" },
  { v: "aurora", label: "星光紫" },
];
</script>

<style scoped>
.tp { display: flex; flex-direction: column; gap: 6px; }
.tp-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; min-height: 38px; padding: 0 4px 0 10px; }
.tp-label { display: flex; align-items: center; gap: 10px; font-size: 14px; white-space: nowrap; }
.tp-seg { display: flex; padding: 3px; border-radius: 10px; background: var(--surface-2); }
.tp-opt { display: flex; align-items: center; gap: 5px; padding: 4px 10px; border-radius: 8px; font-size: 13px; color: var(--muted); cursor: pointer; white-space: nowrap; }
.tp-opt.on { background: var(--surface); color: var(--ink); font-weight: 600; box-shadow: 0 1px 3px rgba(0, 0, 0, 0.12); }
.tp-swatch { width: 12px; height: 12px; border-radius: 50%; border: 1px solid var(--line); }
.tp-swatch.plain { background: #f5f5f7; }
.tp-swatch.aurora { background: linear-gradient(135deg, #b8a6ff, #f3c6e0); }
</style>
