<template>
  <Shell active="plan">
  <view class="page">
    <view v-if="!tripId" class="empty">
      <Icon name="trip" :size="34" color="var(--muted)" />
      <view>还没有要调整的行程</view>
      <button class="btn small" @click="toChat">先在对话里生成一个行程</button>
    </view>
    <template v-else>
      <view class="head">
        <text class="title">第{{ dayNo }}天<text v-if="date"> · {{ date }}</text></text>
        <button class="btn small" :disabled="saving" @click="done">{{ saving ? "保存中…" : "完成" }}</button>
      </view>
      <scroll-view v-if="dayCount > 1" scroll-x class="days">
        <view class="days-inner">
          <text v-for="n in dayCount" :key="n" class="day" :class="{ on: n === dayNo }" @click="switchDay(n)">D{{ n }}</text>
        </view>
      </scroll-view>
      <view class="hint muted">按住右侧 <Icon name="grip" :size="13" color="var(--muted)" /> 拖动调整顺序，时间段不动，按新顺序依次排</view>
      <view class="list">
        <view
          v-for="(item, i) in items"
          :key="item.id"
          class="row"
          :class="{ dragging: dragId === item.id }"
        >
          <image v-if="item.photo && !broken[item.id]" class="thumb" @error="broken[item.id] = true" :src="item.photo" mode="aspectFill" />
          <view v-else class="thumb placeholder"><Icon :name="TYPE_ICON[item.type] || 'pin'" :size="20" color="#fff" /></view>
          <view class="body">
            <view class="name">{{ item.name }}</view>
            <view class="time">{{ slots[i]?.start }}<text v-if="slots[i]?.end"> - {{ slots[i]?.end }}</text></view>
          </view>
          <view class="del" @click="remove(item)"><Icon name="close" :size="14" color="#fff" /></view>
          <view
            class="grip"
            @touchstart.stop.prevent="start(i, $event)"
            @touchmove.stop.prevent="move($event)"
            @touchend="end"
            @mousedown.prevent="start(i, $event)"
          >
            <Icon name="grip" :size="18" color="var(--muted)" />
          </view>
        </view>
      </view>
      <view class="add" @click="add"><Icon name="plus" :size="16" color="var(--accent)" /><text>添加景点/活动</text></view>
      <view class="replan" @click="done">
        <Icon name="sparkle" :size="16" color="var(--skill-schedule)" />
        <text>按新的顺序保存，重新计算路线和时间</text>
        <Icon name="right" :size="16" color="var(--skill-schedule)" />
      </view>
    </template>
  </view>
  </Shell>
</template>

<script setup lang="ts">
import Shell from "../../components/Shell.vue";
import { computed, onUnmounted, reactive, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { api } from "../../api";
import { showError } from "../../api/http";
import type { ItineraryItem, TripDetail } from "../../api/types";
import Icon from "../../components/Icon.vue";

/** 行程调整（原型⑩）：拖动排序、删除；完成后存成新版本，后端按新顺序重算路线。参数 ?id=行程&day=第几天 */
const ROW = 80; // 行高 72 + 间距 8
const TYPE_ICON: Record<string, string> = { SPOT: "pin", FOOD: "food", HOTEL: "bed", TRANSPORT: "car", FREE: "sparkle" };
const tripId = ref(0);
const dayNo = ref(1);
const detail = ref<TripDetail | null>(null);
const items = ref<ItineraryItem[]>([]);
const dragId = ref<string | null>(null);
const saving = ref(false);
/** 加载失败的高德照片 */
const broken = reactive<Record<string, boolean>>({});
let from = 0;
let startY = 0;

const day = computed(() => detail.value?.version.itinerary?.days[dayNo.value - 1]);
const dayCount = computed(() => detail.value?.version.itinerary?.days.length || 0);
const date = computed(() => (day.value ? `${Number(day.value.date.slice(5, 7))}月${Number(day.value.date.slice(8, 10))}日` : ""));
/** 原来的时间段按先后不动，新顺序依次占用（和后端的规则一样） */
const slots = computed(() => day.value?.items || []);

onLoad(async (query) => {
  tripId.value = Number(query?.id) || 0;
  dayNo.value = Number(query?.day) || 1;
  if (tripId.value) await load();
});

async function load() {
  try {
    detail.value = await api.trip(tripId.value);
    if (!detail.value.version.itinerary) {
      uni.showToast({ title: "这个行程是早期保存的，请在对话里让助手调整", icon: "none" });
      return;
    }
    dayNo.value = Math.min(dayNo.value, dayCount.value) || 1;
    items.value = [...(day.value?.items || [])];
  } catch (e) {
    showError(e);
  }
}

function switchDay(n: number) {
  dayNo.value = n;
  items.value = [...(day.value?.items || [])];
}

function pointY(e: any): number {
  return e.touches?.[0]?.clientY ?? e.changedTouches?.[0]?.clientY ?? e.clientY ?? 0;
}

/** 按下拖动柄：记住起点；鼠标操作时在 window 上跟踪移动。 */
function start(i: number, e: any) {
  from = i;
  startY = pointY(e);
  dragId.value = items.value[i].id;
  if (e.type === "mousedown") {
    window.addEventListener("mousemove", move);
    window.addEventListener("mouseup", end);
  }
}

/** 移动超过半行就和相邻项交换位置。 */
function move(e: any) {
  if (!dragId.value) return;
  const target = Math.max(0, Math.min(items.value.length - 1, from + Math.round((pointY(e) - startY) / ROW)));
  if (target !== from) {
    const list = [...items.value];
    const [moved] = list.splice(from, 1);
    list.splice(target, 0, moved);
    items.value = list;
    startY += (target - from) * ROW;
    from = target;
  }
}

function end() {
  dragId.value = null;
  window.removeEventListener("mousemove", move);
  window.removeEventListener("mouseup", end);
}

onUnmounted(end);

/** 删除马上生效（存一个新版本），没保存的顺序调整保留 */
function remove(item: ItineraryItem) {
  uni.showModal({
    title: "删掉这一项",
    content: `从第${dayNo.value}天删掉「${item.name}」？`,
    confirmText: "删除",
    success: async (res) => {
      if (!res.confirm) return;
      try {
        const order = items.value.filter((x) => x.id !== item.id).map((x) => x.id);
        detail.value = await api.removeItem(tripId.value, item.id, null);
        const byId = new Map((day.value?.items || []).map((x) => [x.id, x]));
        items.value = order.map((id) => byId.get(id)!).filter(Boolean);
        uni.showToast({ title: `已删掉，保存为第 ${detail.value.version.version} 版`, icon: "none" });
      } catch (e) {
        showError(e);
      }
    },
  });
}

function add() {
  uni.navigateTo({ url: "/pages/chat/chat?q=" + encodeURIComponent(`行程「${detail.value?.trip.title}」（tripId=${tripId.value}）第${dayNo.value}天再加一个景点`) });
}

function toChat() {
  uni.reLaunch({ url: "/pages/chat/chat" });
}

/** 顺序有变就存新版本并去看前后对比；没变直接回去 */
async function done() {
  const ids = items.value.map((x) => x.id);
  const before = (day.value?.items || []).map((x) => x.id);
  if (ids.join() === before.join()) {
    uni.navigateBack({ fail: () => uni.redirectTo({ url: `/pages/trips/detail?id=${tripId.value}` }) });
    return;
  }
  saving.value = true;
  try {
    const fromVer = detail.value!.version.version;
    const d = await api.reorderDay(tripId.value, dayNo.value, ids);
    uni.redirectTo({ url: `/pages/plan/updated?id=${tripId.value}&from=${fromVer}&to=${d.version.version}` });
  } catch (e) {
    showError(e);
  } finally {
    saving.value = false;
  }
}
</script>

<style scoped>
.page { max-width: 760px; margin: 0 auto; padding: 14px 16px 24px; box-sizing: border-box; }
.head { display: flex; align-items: center; justify-content: space-between; }
.head .btn { margin: 0; }
.title { font-size: 18px; font-weight: 700; }
.days { white-space: nowrap; margin-top: 10px; }
.days-inner { display: inline-flex; gap: 8px; }
.day { padding: 4px 14px; border-radius: 999px; background: var(--surface); border: 1px solid var(--line); font-size: 13px; cursor: pointer; }
.day.on { background: var(--accent); border-color: var(--accent); color: #fff; }
.empty { display: flex; flex-direction: column; align-items: center; gap: 10px; padding: 80px 0; }
.empty .btn { margin: 0; }
.hint { font-size: 12px; margin: 6px 0 12px; display: flex; align-items: center; gap: 2px; }
.list { display: flex; flex-direction: column; gap: 8px; }
.row {
  height: 72px;
  box-sizing: border-box;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  background: var(--surface);
  border-radius: 14px;
  border: 1px solid var(--line);
  transition: box-shadow 0.15s;
}
.row.dragging { box-shadow: 0 8px 24px rgba(37, 99, 235, 0.25); border-color: var(--accent); }
.thumb { width: 64px; height: 54px; border-radius: 10px; flex-shrink: 0; }
.placeholder { background: var(--accent); display: flex; align-items: center; justify-content: center; }
.body { flex: 1; min-width: 0; }
.name { font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.time { font-size: 12px; color: var(--muted); font-variant-numeric: tabular-nums; }
.del { width: 22px; height: 22px; border-radius: 50%; background: #ef4444; display: flex; align-items: center; justify-content: center; cursor: pointer; flex-shrink: 0; }
.grip { padding: 10px 4px; cursor: grab; touch-action: none; }
.add {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  margin-top: 10px;
  padding: 12px;
  border: 1.5px dashed var(--accent);
  border-radius: 14px;
  color: var(--accent);
  font-size: 14px;
  cursor: pointer;
}
.replan {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 14px;
  padding: 12px 14px;
  border-radius: 14px;
  background: var(--skill-schedule-soft);
  color: var(--skill-schedule);
  font-size: 14px;
  cursor: pointer;
}
.replan text { flex: 1; }
@media (prefers-reduced-motion: reduce) { .row { transition: none; } }
@media (min-width: 1024px) {
  .page, .container { max-width: 1180px; padding: 24px 28px; }
  .list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
}
</style>
