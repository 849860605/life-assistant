<template>
  <view class="xcard change">
    <view class="xcard-head">
      <text class="xcard-title">改了行程 · {{ data.title }}</text>
      <text class="xcard-meta">第 {{ data.fromVersion }} → {{ data.version }} 版</text>
    </view>
    <view v-for="(c, i) in data.changes" :key="i" class="xcard-row line">
      <view class="dot" />
      <text>{{ c }}</text>
    </view>
    <view class="actions">
      <text v-if="state === 'undone'" class="muted done">已撤销，回到第 {{ data.fromVersion }} 版</text>
      <button v-else class="btn small ghost" :disabled="state === 'busy'" @click="undo">{{ state === "busy" ? "撤销中…" : "撤销" }}</button>
      <button class="btn small" @click="openTrip">查看行程</button>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { api } from "../../api";
import { TRIP_CHANGED } from "../../utils/events";

/**
 * 改动卡：对话里局部改行程（换、删、加某几项，对调、重排某几天，换酒店）后发来的，列出改了什么。
 * 撤销 = 把改之前那一版恢复成新版本（历史不删）；之后行程又改过时先提醒，后来的改动也会一起撤掉。
 * 撤过没有看版本历史：这一版之后有"恢复到第 N 版"（N 是改之前那一版）就算撤过，刷新页面后也能显示。
 */
const props = defineProps<{ data: any }>();

const state = ref<"idle" | "busy" | "undone">("idle");

api.tripVersions(props.data.tripId).then((list) => {
  const note = `恢复到第 ${props.data.fromVersion} 版`;
  if (state.value === "idle" && list.some((v) => v.version > props.data.version && v.changeNote === note)) state.value = "undone";
}).catch(() => {});

function openTrip() {
  uni.navigateTo({ url: `/pages/trips/detail?id=${props.data.tripId}` });
}

/** 行程现在不是这张卡改出来的那一版时，问一下再撤 */
async function undo() {
  if (state.value !== "idle") return;
  state.value = "busy";
  try {
    const now = await api.trip(props.data.tripId);
    if (now.trip.currentVer !== props.data.version) {
      const ok = await new Promise<boolean>((resolve) =>
        uni.showModal({
          title: "行程之后又改过",
          content: `现在是第 ${now.trip.currentVer} 版。撤销会回到第 ${props.data.fromVersion} 版，之后的改动也会一起撤掉。`,
          confirmText: "仍然撤销",
          success: (r) => resolve(r.confirm),
          fail: () => resolve(false),
        }),
      );
      if (!ok) {
        state.value = "idle";
        return;
      }
    }
    await api.restoreVersion(props.data.tripId, props.data.fromVersion);
    state.value = "undone";
    uni.$emit(TRIP_CHANGED, props.data.tripId);
  } catch (e) {
    state.value = "idle";
    uni.showToast({ title: e instanceof Error ? e.message : "撤销失败", icon: "none" });
  }
}
</script>

<style scoped>
.line { display: flex; align-items: flex-start; gap: 8px; font-size: 14px; line-height: 1.5; border-bottom: none; padding-bottom: 2px; }
.dot { width: 6px; height: 6px; border-radius: 50%; background: var(--accent); margin-top: 8px; flex-shrink: 0; }
.actions { display: flex; justify-content: flex-end; align-items: center; gap: 8px; padding: 10px 14px 12px; }
.actions .btn { margin: 0; }
.done { font-size: 13px; }
</style>
