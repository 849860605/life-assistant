<template>
  <Shell active="trips">
    <view class="page-wrap">
      <ArrangeTabs :model-value="'trips'" @update:model-value="toSchedule" />
      <TripsView ref="view" />
    </view>
  </Shell>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import ArrangeTabs from "../../components/ArrangeTabs.vue";
import Shell from "../../components/Shell.vue";
import TripsView from "../../components/TripsView.vue";

/** 我的行程：「我的安排」的行程标签。列表内容在 TripsView 里，和日程页共用。 */
const view = ref<InstanceType<typeof TripsView> | null>(null);
let shown = false;

// 第一次由列表自己加载，之后回到页面再刷新
onShow(() => {
  if (shown) view.value?.load();
  shown = true;
});

function toSchedule() {
  uni.reLaunch({ url: "/pages/schedule/schedule" });
}
</script>
