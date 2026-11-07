<template>
  <view class="cs">
    <view class="cs-head">
      <view class="cs-new" @click="emit('new')"><Icon name="plus" :size="16" color="var(--ink)" /><text>新对话</text></view>
      <view v-if="collapsible" class="cs-fold" title="收起" aria-label="收起对话列表" @click="emit('collapse')"><Icon name="back" :size="16" color="var(--muted)" /></view>
    </view>
    <view class="cs-search">
      <Icon name="search" :size="14" color="var(--muted)" />
      <input id="conv-search" v-model="keyword" class="cs-search-input" placeholder="搜索对话" />
      <view v-if="keyword" class="cs-clear" @click="keyword = ''"><Icon name="close" :size="12" color="var(--muted)" /></view>
    </view>
    <view class="cs-title">{{ keyword ? "搜索结果" : "最近对话" }}</view>
    <scroll-view scroll-y class="cs-list">
      <view v-for="c in list" :key="c.id" class="cs-item" :class="{ on: c.id === currentId }" @click="renamingId !== c.id && emit('open', c)">
        <view v-if="renamingId === c.id" class="cs-rename" @click.stop>
          <input :id="`conv-rename-${c.id}`" v-model="renameText" class="input cs-input" maxlength="60" :focus="true" @confirm="saveRename(c)" @blur="saveRename(c)" />
        </view>
        <template v-else>
          <Icon v-if="c.pinnedAt" name="flag" :size="12" color="var(--accent)" />
          <text class="cs-text">{{ c.title || "新对话" }}</text>
          <ConvMenu class="cs-menu" :conv="c" @rename="startRename(c)" @pinned="load" @deleted="onDeleted(c)" />
        </template>
      </view>
      <view v-if="loaded && !list.length" class="cs-empty">{{ keyword ? `没找到含"${keyword}"的对话` : "还没有对话" }}</view>
    </scroll-view>
    <view class="cs-me" @click="goMe">
      <view class="cs-avatar">{{ (nickname || "我").slice(0, 1).toUpperCase() }}</view>
      <text class="cs-name">{{ nickname || "我的" }}</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from "vue";
import { api } from "../api";
import { showError } from "../api/http";
import type { Conversation } from "../api/types";
import ConvMenu from "./ConvMenu.vue";
import Icon from "./Icon.vue";

/**
 * 对话左边栏（首页和对话页共用，参考千问）：新对话、搜索（标题和聊天内容）、最近对话（置顶的在前），
 * 每条有"…"菜单（重命名、置顶、分享、导出、删除），底部是自己的头像，点了进"我的"。
 */
const props = withDefaults(defineProps<{ currentId?: string | null; collapsible?: boolean }>(), { currentId: null, collapsible: false });
const emit = defineEmits<{
  (e: "open", c: Conversation): void;
  (e: "new"): void;
  (e: "deleted", c: Conversation): void;
  (e: "collapse"): void;
}>();

const list = ref<Conversation[]>([]);
const loaded = ref(false);
const keyword = ref("");
const nickname = ref("");
const renamingId = ref("");
const renameText = ref("");
let timer: ReturnType<typeof setTimeout> | null = null;

onMounted(() => {
  load();
  api.me().then((u) => (nickname.value = u.nickname || u.username)).catch(() => {});
});
// 搜索停顿 300 毫秒再查
watch(keyword, () => {
  if (timer) clearTimeout(timer);
  timer = setTimeout(load, 300);
});
defineExpose({ load });

async function load() {
  try {
    list.value = await api.conversations(keyword.value.trim());
  } catch (e) {
    showError(e);
  } finally {
    loaded.value = true;
  }
}

function startRename(c: Conversation) {
  renamingId.value = c.id;
  renameText.value = c.title || "";
}

/** 回车或点别处保存；没改就不发请求 */
async function saveRename(c: Conversation) {
  if (renamingId.value !== c.id) return;
  const title = renameText.value.trim();
  renamingId.value = "";
  if (!title || title === c.title) return;
  try {
    c.title = (await api.renameConversation(c.id, title)).title;
  } catch (e) {
    showError(e);
  }
}

async function onDeleted(c: Conversation) {
  emit("deleted", c);
  await load();
}

function goMe() {
  uni.reLaunch({ url: "/pages/me/me" });
}

// 当前会话变了（比如刚发出第一条、标题生成了），刷新一下列表
watch(() => props.currentId, () => load());
</script>

<style scoped>
.cs { width: 260px; flex-shrink: 0; height: 100%; display: flex; flex-direction: column; background: var(--surface-2); border-right: 1px solid var(--line); padding: 12px 10px; box-sizing: border-box; }
.cs-head { display: flex; align-items: center; gap: 4px; }
.cs-new { flex: 1; display: flex; align-items: center; gap: 8px; height: 38px; padding: 0 10px; border-radius: 8px; font-size: 15px; cursor: pointer; }
.cs-fold { width: 32px; height: 32px; border-radius: 8px; display: flex; align-items: center; justify-content: center; cursor: pointer; }
.cs-new:hover, .cs-fold:hover, .cs-item:hover, .cs-me:hover { background: rgba(0, 0, 0, 0.05); }
.cs-search { display: flex; align-items: center; gap: 6px; height: 34px; margin: 8px 2px 0; padding: 0 10px; border-radius: 8px; background: var(--surface); border: 1px solid var(--line); }
.cs-search-input { flex: 1; font-size: 13px; }
.cs-clear { display: flex; cursor: pointer; }
.cs-title { font-size: 12px; color: var(--muted); padding: 14px 10px 6px; }
.cs-list { flex: 1; height: 0; }
.cs-item { display: flex; align-items: center; gap: 6px; height: 36px; padding: 0 6px 0 10px; border-radius: 8px; font-size: 14px; cursor: pointer; }
.cs-item.on { background: var(--surface); box-shadow: inset 0 0 0 1px var(--line); font-weight: 600; }
.cs-text { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.cs-menu { opacity: 0; }
.cs-item:hover .cs-menu, .cs-item.on .cs-menu { opacity: 1; }
@media (hover: none) { .cs-menu { opacity: 1; } }
.cs-rename { flex: 1; }
.cs-input { height: 30px; font-size: 14px; width: 100%; }
.cs-empty { padding: 8px 10px; font-size: 13px; color: var(--muted); }
.cs-me { display: flex; align-items: center; gap: 10px; padding: 8px 10px; border-radius: 8px; cursor: pointer; margin-top: 8px; }
.cs-avatar { width: 28px; height: 28px; border-radius: 50%; background: var(--ink); color: var(--surface); font-size: 12px; font-weight: 600; display: flex; align-items: center; justify-content: center; }
.cs-name { font-size: 14px; }
</style>
