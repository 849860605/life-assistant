<template>
  <view class="bt" @click.stop>
    <text class="bt-tag" :class="state" @click="open = !open">{{ LABEL[state] }}</text>
    <view v-if="open" class="bt-menu">
      <text v-if="url" class="bt-item" @click="pick('open')">去平台预订 ›</text>
      <text v-if="state !== 'CONFIRMED'" class="bt-item" @click="pick('CONFIRMED')">我已订好</text>
      <text v-if="state !== 'PLANNED'" class="bt-item" @click="pick('PLANNED')">改回计划预订</text>
      <text class="bt-note">点过预订链接只记"已跳转平台"，订好后要你自己点"我已订好"</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from "vue";
import type { BookingState } from "../api/types";

/**
 * 一项安排或住宿的预订状态：计划预订、已跳转平台（点过预订链接，不算订好）、已确认订单（用户自己确认）。
 * 点标签展开操作；去预订发 open，改状态发 set。
 */
defineProps<{ state: BookingState; url: string | null }>();
const emit = defineEmits<{ (e: "set", s: BookingState): void; (e: "open"): void }>();

const LABEL: Record<BookingState, string> = { PLANNED: "计划预订", OPENED: "已跳转平台 · 待确认", CONFIRMED: "已确认订单" };
const open = ref(false);

function pick(a: BookingState | "open") {
  open.value = false;
  if (a === "open") emit("open");
  else emit("set", a);
}
</script>

<style scoped>
.bt { position: relative; }
.bt-tag { font-size: 11px; padding: 2px 8px; border-radius: 6px; cursor: pointer; white-space: nowrap; background: var(--surface-2); color: var(--muted); border: 1px solid var(--line); }
.bt-tag.OPENED { background: #fff4ea; color: #c2560c; border-color: #fed7aa; }
.bt-tag.CONFIRMED { background: var(--ok-soft); color: var(--ok); border-color: transparent; }
.bt-menu { position: absolute; right: 0; top: calc(100% + 4px); z-index: 20; width: 200px; display: flex; flex-direction: column; padding: 6px; border-radius: 10px; background: var(--surface); border: 1px solid var(--line); box-shadow: 0 10px 30px rgba(15, 23, 42, 0.15); }
.bt-item { font-size: 13px; padding: 7px 8px; border-radius: 6px; cursor: pointer; }
.bt-item:hover { background: var(--surface-2); }
.bt-note { font-size: 11px; color: var(--muted); padding: 6px 8px 2px; line-height: 1.5; }
</style>
