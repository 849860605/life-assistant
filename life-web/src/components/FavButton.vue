<template>
  <view class="fav" :class="{ on, labeled: !!label }" role="button" :aria-label="on ? '取消收藏' : '收藏'" @click.stop="toggle">
    <Icon name="heart" :size="size" :fill="on" :color="on ? 'var(--danger)' : 'var(--muted)'" />
    <text v-if="label">{{ on ? "已收藏" : label }}</text>
  </view>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import Icon from "./Icon.vue";
import type { Favorite } from "../api/types";
import { favKey, favs, loadFavorites, toggleFavorite } from "../utils/favorites";

/** 收藏按钮（心形）：传入要收藏的东西，点了收藏或取消；label 不为空时旁边带文字。 */
const props = withDefaults(defineProps<{ item: Omit<Favorite, "id" | "createdAt">; label?: string; size?: number }>(), { label: "", size: 16 });
const busy = ref(false);
const on = computed(() => !!favs.value[favKey(props.item.kind, props.item.refKey)]);

onMounted(() => loadFavorites());

async function toggle() {
  if (busy.value) return;
  busy.value = true;
  await toggleFavorite(props.item);
  busy.value = false;
}
</script>

<style scoped>
.fav { display: inline-flex; align-items: center; justify-content: center; gap: 4px; cursor: pointer; flex-shrink: 0; min-width: 28px; min-height: 28px; font-size: 13px; color: var(--muted); }
.fav.labeled { padding: 0 10px; border: 1px solid var(--line); border-radius: 8px; height: 32px; box-sizing: border-box; }
.fav.on { color: var(--danger); }
</style>
