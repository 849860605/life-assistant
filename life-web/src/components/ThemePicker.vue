<template>
  <view class="tp">
    <view class="tp-row">
      <view class="tp-label"><Icon name="sparkle" :size="16" color="var(--ink)" /><text>外观</text></view>
      <view class="tp-seg">
        <view v-for="o in APPEARANCES" :key="o.v" class="tp-opt" :class="{ on: theme.appearance === o.v }" @click="setAppearance(o.v)">
          <view v-if="o.v !== 'system'" class="tp-swatch" :class="o.v" /><text>{{ o.label }}</text>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { setAppearance, theme, type Appearance } from "../utils/theme";
import Icon from "./Icon.vue";

/** 外观设置（账号菜单和"我的"页共用）：浅色 / 星光紫 / 深色 / 跟随系统；只存在这台设备上 */
const APPEARANCES: { v: Appearance; label: string }[] = [
  { v: "light", label: "浅色" },
  { v: "aurora", label: "星光紫" },
  { v: "dark", label: "深色" },
  { v: "system", label: "跟随系统" },
];
</script>

<style scoped>
.tp { display: flex; flex-direction: column; gap: 6px; }
.tp-row { display: flex; flex-direction: column; gap: 8px; padding: 8px 4px 4px 10px; }
.tp-label { display: flex; align-items: center; gap: 10px; font-size: 14px; white-space: nowrap; }
.tp-seg { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); padding: 3px; border-radius: 10px; background: var(--surface-2); }
.tp-opt { display: flex; align-items: center; justify-content: center; gap: 5px; padding: 6px 4px; border-radius: 8px; font-size: 13px; color: var(--muted); cursor: pointer; white-space: nowrap; }
.tp-opt.on { background: var(--surface); color: var(--ink); font-weight: 600; box-shadow: 0 1px 3px rgba(0, 0, 0, 0.12); }
.tp-swatch { width: 12px; height: 12px; border-radius: 50%; border: 1px solid var(--line); }
.tp-swatch.light { background: #f5f5f7; }
.tp-swatch.dark { background: #1c1c1e; }
.tp-swatch.aurora { background: linear-gradient(135deg, #b8a6ff, #f3c6e0); }
</style>
