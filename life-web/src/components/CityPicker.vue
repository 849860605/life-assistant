<template>
  <view class="mask" @click="emit('close')">
    <view class="box" @click.stop>
      <view class="head">
        <text class="title">切换城市</text>
        <view class="x" @click="emit('close')"><Icon name="close" :size="18" color="var(--muted)" /></view>
      </view>
      <view class="input-row">
        <Icon name="search" :size="16" color="var(--muted)" />
        <input id="city-input" v-model="text" class="input" placeholder="输入城市名，例如：大理" confirm-type="done" @confirm="pick(text)" />
        <button class="btn small" @click="pick(text)">确定</button>
      </view>
      <view class="label">常用城市</view>
      <view class="cities">
        <text v-for="c in CITIES" :key="c" class="city" :class="{ on: c === current }" @click="pick(c)">{{ c }}</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from "vue";
import Icon from "./Icon.vue";

/** 选城市的弹层：常用城市一键选，或者输入城市名。选中后发 pick 事件，由外壳保存并刷新页面。 */
defineProps<{ current: string }>();
const emit = defineEmits<{ (e: "close"): void; (e: "pick", city: string): void }>();

const CITIES = ["北京", "上海", "广州", "深圳", "成都", "重庆", "杭州", "西安", "南京", "武汉", "长沙", "厦门", "青岛", "昆明", "大理", "三亚"];
const text = ref("");

function pick(city: string) {
  const c = city.trim().replace(/市$/, "");
  if (c) emit("pick", c);
}
</script>

<style scoped>
.mask { position: fixed; inset: 0; z-index: 100; background: rgba(15, 23, 42, 0.35); display: flex; align-items: flex-start; justify-content: center; padding-top: 12vh; }
.box { width: min(440px, calc(100vw - 32px)); background: var(--surface); border-radius: 16px; padding: 16px; box-shadow: 0 20px 50px rgba(15, 23, 42, 0.25); }
.head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.title { font-size: 16px; font-weight: 700; }
.x { cursor: pointer; padding: 2px; }
.input-row { display: flex; align-items: center; gap: 8px; padding: 6px 6px 6px 12px; border-radius: 10px; background: var(--surface-2); }
.input { flex: 1; font-size: 14px; height: 32px; }
.input-row .btn { margin: 0; }
.label { font-size: 13px; color: var(--muted); margin: 14px 0 8px; }
.cities { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; }
.city { text-align: center; padding: 8px 0; border-radius: 10px; border: 1px solid var(--line); font-size: 14px; cursor: pointer; }
.city:hover { border-color: var(--accent); }
.city.on { background: var(--accent-soft); border-color: var(--accent); color: var(--accent); font-weight: 600; }
</style>
