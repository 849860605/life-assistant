<template>
  <view class="leg-row">
    <view class="leg-main">
      <Icon :name="ICON[leg.mode] || 'car'" :size="13" color="var(--muted)" />
      <text class="leg-text">{{ prefix }}{{ legText(leg) }}<text v-if="leg.mode === 'TRANSIT' && leg.transit?.walkKm" class="leg-walk"> · 共步行 {{ leg.transit.walkKm }} 公里</text></text>
    </view>
    <view v-if="hint" class="leg-hint"><Icon name="flag" :size="12" color="var(--price)" />{{ hint }}</view>
    <view v-if="alt" class="leg-alt">
      <text>{{ alt }}</text>
      <text v-if="editable" class="leg-switch" :class="{ busy }" @click.stop="change">{{ other === "DRIVE" ? "改打车" : "改坐地铁公交" }}</text>
    </view>
  </view>
</template>

<script setup lang="ts">
// 行程里两站之间的一段路：所选方式（地铁公交 / 打车 / 步行）、不太适合坐地铁时的提醒、另一种方式和一键切换
import { computed } from "vue";
import Icon from "./Icon.vue";
import type { ItineraryDay, ItineraryLeg, Stay } from "../api/types";
import { legAltText, legHint, legText } from "../utils/place";

const props = defineProps<{ leg: ItineraryLeg; day?: ItineraryDay | null; stays?: Stay[]; editable?: boolean; busy?: boolean; prefix?: string }>();
const emit = defineEmits<{ (e: "mode", mode: "TRANSIT" | "DRIVE"): void }>();

const ICON: Record<string, string> = { WALK: "users", TRANSIT: "bus", DRIVE: "car" };
const hint = computed(() => legHint(props.leg, props.day, props.stays));
const alt = computed(() => legAltText(props.leg));
const other = computed(() => (props.leg.mode === "TRANSIT" ? "DRIVE" : "TRANSIT"));

function change() {
  if (!props.busy) emit("mode", other.value);
}
</script>

<style scoped>
.leg-row { display: flex; flex-direction: column; gap: 4px; font-size: 13px; color: var(--muted); }
.leg-main { display: flex; align-items: flex-start; gap: 6px; }
.leg-main :deep(.icon), .leg-main > :first-child { flex: none; margin-top: 3px; }
.leg-text { flex: 1; min-width: 0; color: var(--ink); line-height: 1.5; }
.leg-walk { font-size: 12px; color: var(--muted); }
.leg-hint { display: inline-flex; align-self: flex-start; align-items: center; gap: 4px; font-size: 12px; color: var(--price); background: var(--surface-2); border-radius: 6px; padding: 2px 8px; }
.leg-alt { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; font-size: 12px; padding-left: 19px; }
.leg-switch { color: var(--accent); cursor: pointer; white-space: nowrap; }
.leg-switch.busy { opacity: 0.5; }
</style>
