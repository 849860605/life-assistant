<template>
  <Shell active="flight">
  <view class="page">
    <view class="head"><text class="title">西安 · 酒店推荐</text></view>
    <HotelList />
  </view>
  </Shell>
</template>

<script setup lang="ts">
import Shell from "../../components/Shell.vue";
import HotelList from "../../components/HotelList.vue";

/** 酒店推荐（原型⑨）的独立页，内容和机票页里的"酒店"标签相同。 */
</script>

<style scoped>
.page { max-width: 760px; margin: 0 auto; padding: 14px 16px 24px; box-sizing: border-box; }
.head { margin-bottom: 12px; }
.title { font-size: 18px; font-weight: 700; }
@media (min-width: 1024px) {
  .page, .container { max-width: 1180px; padding: 24px 28px; }
  .list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
}
</style>
