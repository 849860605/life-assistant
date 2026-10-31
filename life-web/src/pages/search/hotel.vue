<template>
  <Shell active="flight">
  <view class="page">
    <view class="head"><text class="title">酒店比价</text></view>
    <HotelList v-if="ready" :initial="query" />
  </view>
  </Shell>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import Shell from "../../components/Shell.vue";
import HotelList from "../../components/HotelList.vue";

/** 酒店比价（原型⑨）的独立页，内容和机票页里的"酒店"标签相同；支持 ?city=&place=&checkIn=&checkOut=。 */
const query = ref<Record<string, string | undefined>>({});
const ready = ref(false);

onLoad((q) => {
  query.value = q || {};
  ready.value = true;
});
</script>

<style scoped>
.page { max-width: 760px; margin: 0 auto; padding: 14px 16px 24px; box-sizing: border-box; }
.head { margin-bottom: 12px; }
.title { font-size: 18px; font-weight: 700; }
@media (min-width: 1024px) {
  .page, .container { max-width: 1180px; padding: 24px 28px; }
}
</style>
