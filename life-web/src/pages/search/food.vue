<template>
  <Shell active="food">
  <view class="page">
    <view class="head">
      <text class="title">第1天 · 附近美食</text>
      <text class="tag">示例</text>
    </view>
    <scroll-view scroll-x class="cats">
      <view class="cats-inner">
        <text v-for="c in CATS" :key="c" class="cat" :class="{ on: cat === c }" @click="cat = c">{{ c }}</text>
      </view>
    </scroll-view>
    <view class="filters">
      <view v-for="f in FILTERS" :key="f" class="filter">{{ f }}<Icon name="down" :size="12" color="var(--muted)" /></view>
      <view class="filter map" @click="goMap"><Icon name="pin" :size="12" color="var(--accent)" />地图模式</view>
    </view>
    <view class="list">
      <view v-for="f in list" :key="f.name" class="item">
        <image class="img" :src="f.image" mode="aspectFill" />
        <view class="info">
          <view class="name">{{ f.name }}</view>
          <view class="meta"><text class="rating">★ {{ f.rating }}</text><text class="muted"> 高德评分</text></view>
          <view class="meta muted">¥{{ f.avgPrice }}/人 · 距行程点 {{ f.distance }}</view>
          <view class="tags"><text v-for="t in f.tags" :key="t" class="tag-chip">{{ t }}</text></view>
        </view>
        <view class="side">
          <button class="btn small add" :class="{ added: added[f.name] }" @click="add(f.name)">{{ added[f.name] ? "已加入" : "加入行程" }}</button>
        </view>
      </view>
      <view v-if="!list.length" class="empty">这个分类暂时没有推荐</view>
    </view>
    <view class="ask" @click="ask">想找别的？<text class="link">跟助手说说想吃什么</text></view>
  </view>
  </Shell>
</template>

<script setup lang="ts">
import Shell from "../../components/Shell.vue";
import { computed, reactive, ref } from "vue";
import Icon from "../../components/Icon.vue";
import { FOODS } from "../../demo/xian";

/** 美食推荐（原型⑦）：按分类筛选行程附近的餐厅，加入行程。 */
const CATS = ["全部", "火锅", "川菜", "小吃", "甜品"];
const FILTERS = ["人均预算", "距离排序", "评分优先"];
const cat = ref("全部");
const added = reactive<Record<string, boolean>>({});
const list = computed(() => (cat.value === "全部" ? FOODS : FOODS.filter((f) => f.category === cat.value)));

function add(name: string) {
  added[name] = !added[name];
  if (added[name]) uni.showToast({ title: "已加入第1天晚餐", icon: "none" });
}

function goMap() {
  uni.navigateTo({ url: "/pages/plan/map" });
}

function ask() {
  uni.navigateTo({ url: "/pages/chat/chat?q=" + encodeURIComponent("回民街附近有什么好吃的？带孩子") });
}
</script>

<style scoped>
.page { max-width: 760px; margin: 0 auto; padding: 14px 16px 24px; box-sizing: border-box; }
.head { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.title { font-size: 18px; font-weight: 700; }
.tag { font-size: 11px; padding: 2px 8px; border-radius: 999px; background: var(--surface-2); color: var(--muted); }
.cats { white-space: nowrap; }
.cats-inner { display: inline-flex; gap: 8px; }
.cat { font-size: 14px; padding: 6px 16px; border-radius: 999px; background: var(--surface); border: 1px solid var(--line); cursor: pointer; }
.cat.on { background: var(--accent); border-color: var(--accent); color: #fff; }
.filters { display: flex; gap: 14px; align-items: center; margin: 12px 2px; font-size: 13px; color: var(--muted); flex-wrap: wrap; }
.filter { display: flex; align-items: center; gap: 2px; cursor: pointer; }
.filter.map { margin-left: auto; color: var(--accent); border: 1px solid var(--accent); padding: 3px 10px; border-radius: 999px; gap: 4px; }
.list { display: flex; flex-direction: column; gap: 10px; }
.item { display: flex; gap: 10px; background: var(--surface); border-radius: 14px; padding: 10px; border: 1px solid var(--line); }
.img { width: 96px; height: 96px; border-radius: 10px; flex-shrink: 0; }
.info { flex: 1; min-width: 0; }
.name { font-weight: 700; }
.meta { font-size: 12px; margin-top: 2px; }
.rating { color: var(--price); font-weight: 600; }
.tags { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 6px; }
.tag-chip { font-size: 11px; padding: 1px 6px; border-radius: 4px; background: #fff4ea; color: #c2560c; }
.side { display: flex; flex-direction: column; justify-content: flex-end; }
.add { margin: 0; }
.add.added { background: var(--ok) !important; }
.ask { text-align: center; font-size: 13px; color: var(--muted); margin-top: 16px; cursor: pointer; }
@media (min-width: 1024px) {
  .page, .container { max-width: 1180px; padding: 24px 28px; }
  .list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
}
</style>
