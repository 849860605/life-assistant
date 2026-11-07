<template>
  <view class="vb" :class="{ rec: state === 'recording', busy: state === 'busy' }" role="button"
        :aria-label="state === 'recording' ? '停止录音' : '语音输入'" :title="state === 'recording' ? '点一下结束' : '语音输入'" @click="toggle">
    <view v-if="state === 'recording'" class="vb-dot" />
    <Icon v-else name="mic" :size="17" :color="state === 'busy' ? 'var(--muted)' : 'var(--ink)'" />
    <text v-if="state === 'recording'" class="vb-time">{{ seconds }}″</text>
    <text v-else-if="state === 'busy'" class="vb-time">识别中…</text>
  </view>
</template>

<script setup lang="ts">
import { onUnmounted, ref } from "vue";
import Icon from "./Icon.vue";
import { api } from "../api";
import { MAX_SECONDS, RecorderError, startRecording, type Recording } from "../utils/recorder";

/**
 * 语音输入按钮：点一下开始录音，再点一下结束（最长 60 秒自动结束），转成文字后交给外面放进输入框，
 * 用户看过、改完再发，不会直接替用户发出去。
 */
const emit = defineEmits<{ (e: "text", text: string): void }>();
const state = ref<"idle" | "recording" | "busy">("idle");
const seconds = ref(0);
let rec: Recording | null = null;

async function toggle() {
  if (state.value === "busy") return;
  if (state.value === "recording") return finish();
  try {
    seconds.value = 0;
    rec = await startRecording((s) => (seconds.value = s), finish);
    state.value = "recording";
  } catch (e) {
    uni.showToast({ title: e instanceof RecorderError ? e.message : "录音失败", icon: "none", duration: 3000 });
  }
}

async function finish() {
  if (!rec || state.value !== "recording") return;
  const r = rec;
  rec = null;
  state.value = "busy";
  try {
    const wav = await r.stop();
    if (!wav) {
      uni.showToast({ title: "没听到声音，靠近麦克风再说一次", icon: "none" });
      return;
    }
    const file = new File([wav], "voice.wav", { type: "audio/wav" });
    const { text } = await api.transcribe(file);
    if (text) emit("text", text);
    else uni.showToast({ title: "没听清，再说一次试试", icon: "none" });
  } catch (e) {
    uni.showToast({ title: e instanceof Error ? e.message : "识别失败", icon: "none", duration: 3000 });
  } finally {
    state.value = "idle";
  }
}

onUnmounted(() => rec?.cancel());
defineExpose({ MAX_SECONDS });
</script>

<style scoped>
.vb { display: flex; align-items: center; justify-content: center; gap: 4px; min-width: 34px; height: 34px; padding: 0 8px; border-radius: 17px; cursor: pointer; box-sizing: border-box; flex-shrink: 0; }
.vb:hover { background: var(--surface-2); }
.vb.rec { background: var(--danger-soft); color: var(--danger); }
.vb.busy { cursor: default; color: var(--muted); }
.vb-dot { width: 10px; height: 10px; border-radius: 50%; background: var(--danger); animation: pulse 1s ease-in-out infinite; }
.vb-time { font-size: 13px; font-variant-numeric: tabular-nums; }
@keyframes pulse { 50% { opacity: 0.35; } }
@media (prefers-reduced-motion: reduce) { .vb-dot { animation: none; } }
</style>
