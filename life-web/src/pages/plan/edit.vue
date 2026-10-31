<template>
  <Shell active="plan">
  <view class="page">
    <view class="head">
      <text class="title">第1天 · 3月10日</text>
      <button class="btn small" @click="done">完成</button>
    </view>
    <view class="hint muted">按住右侧 <Icon name="grip" :size="13" color="var(--muted)" /> 拖动调整顺序</view>
    <view class="list">
      <view
        v-for="(item, i) in items"
        :key="item.id"
        class="row"
        :class="{ dragging: dragId === item.id }"
      >
        <image v-if="imageFor(item.name)" class="thumb" :src="imageFor(item.name)" mode="aspectFill" />
        <view v-else class="thumb placeholder"><Icon name="pin" :size="20" color="#fff" /></view>
        <view class="body">
          <view class="name">{{ item.name }}</view>
          <view class="time">{{ item.start }} - {{ item.end }}</view>
        </view>
        <view class="del" @click="remove(i)"><Icon name="close" :size="14" color="#fff" /></view>
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
      <text>根据新的顺序，重新规划路线和时间</text>
      <Icon name="right" :size="16" color="var(--skill-schedule)" />
    </view>
  </view>
  </Shell>
</template>

<script setup lang="ts">
import Shell from "../../components/Shell.vue";
import { onUnmounted, ref } from "vue";
import Icon from "../../components/Icon.vue";
import { DEMO_TRIP, imageFor } from "../../demo/xian";

/** 行程调整（原型⑩）：拖动排序、删除、添加；完成后重新规划路线和时间。 */
const ROW = 80; // 行高 72 + 间距 8
const items = ref([...DEMO_TRIP.days[0].items]);
const dragId = ref<string | null>(null);
let from = 0;
let startY = 0;

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

function remove(i: number) {
  items.value.splice(i, 1);
}

function add() {
  uni.navigateTo({ url: "/pages/plan/replace?name=" + encodeURIComponent("新增景点") });
}

function done() {
  uni.redirectTo({ url: "/pages/plan/updated" });
}
</script>

<style scoped>
.page { max-width: 760px; margin: 0 auto; padding: 14px 16px 24px; box-sizing: border-box; }
.head { display: flex; align-items: center; justify-content: space-between; }
.head .btn { margin: 0; }
.title { font-size: 18px; font-weight: 700; }
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
