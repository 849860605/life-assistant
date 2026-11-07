<template>
  <view class="arrange-head">
    <text class="page-title">我的安排</text>
    <view class="seg" role="tablist">
      <view class="seg-item" :class="{ on: modelValue === 'schedule' }" role="tab" @click="pick('schedule')">日程</view>
      <view class="seg-item" :class="{ on: modelValue === 'trips' }" role="tab" @click="pick('trips')">行程</view>
    </view>
  </view>
</template>

<script setup lang="ts">
/** 「我的安排」页头：标题和日程 / 行程两个标签。 */
const props = defineProps<{ modelValue: "schedule" | "trips" }>();
const emit = defineEmits<{ (e: "update:modelValue", v: "schedule" | "trips"): void }>();

function pick(v: "schedule" | "trips") {
  if (v !== props.modelValue) emit("update:modelValue", v);
}
</script>

<style scoped>
.arrange-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
</style>
