<template>
  <view class="cm" @click.stop>
    <view class="cm-trigger" :class="{ open }" role="button" aria-label="更多操作" @click="toggle"><Icon name="more" :size="16" color="var(--muted)" /></view>
    <template v-if="open">
      <view class="cm-mask" @click="open = false" />
      <view class="cm-menu" :class="{ up }" role="menu">
        <view class="cm-item" role="menuitem" @click="act('rename')"><Icon name="bookmark" :size="15" color="var(--ink)" /><text>重命名</text></view>
        <view class="cm-item" role="menuitem" @click="act('pin')"><Icon name="flag" :size="15" color="var(--ink)" /><text>{{ conv.pinnedAt ? "取消置顶" : "置顶此对话" }}</text></view>
        <view class="cm-item" role="menuitem" @click="act('share')"><Icon name="send" :size="15" color="var(--ink)" /><text>分享此对话</text></view>
        <view class="cm-item" role="menuitem" @click="act('export')"><Icon name="layers" :size="15" color="var(--ink)" /><text>导出对话</text></view>
        <view class="cm-line" />
        <view class="cm-item danger" role="menuitem" @click="act('delete')"><Icon name="trash" :size="15" color="var(--danger)" /><text>删除此对话</text></view>
      </view>
    </template>
  </view>
</template>

<script setup lang="ts">
import { ref } from "vue";
import Icon from "./Icon.vue";
import type { Conversation } from "../api/types";
import { deleteConversation, exportConversation, shareConversation, togglePin } from "../utils/conversations";

/**
 * 对话的"…"菜单：重命名、置顶、分享（复制成文字）、导出（下载 Markdown）、删除（要确认）。
 * 重命名交给外面做成就地编辑；置顶和删除做完通知外面刷新。
 */
const props = defineProps<{ conv: Conversation }>();
const emit = defineEmits<{
  (e: "rename"): void;
  (e: "pinned", c: Conversation): void;
  (e: "deleted"): void;
}>();
const open = ref(false);
/** 离窗口底部太近时往上弹，免得被列表裁掉 */
const up = ref(false);

function toggle(e: any) {
  // #ifdef H5
  const rect = (e?.currentTarget as HTMLElement | undefined)?.getBoundingClientRect?.();
  up.value = !!rect && rect.bottom > window.innerHeight - 280;
  // #endif
  open.value = !open.value;
}

async function act(what: "rename" | "pin" | "share" | "export" | "delete") {
  open.value = false;
  if (what === "rename") emit("rename");
  else if (what === "share") shareConversation(props.conv);
  else if (what === "export") exportConversation(props.conv);
  else if (what === "pin") {
    const c = await togglePin(props.conv);
    if (c) emit("pinned", c);
  } else if (await deleteConversation(props.conv)) emit("deleted");
}
</script>

<style scoped>
.cm { position: relative; flex-shrink: 0; }
.cm-trigger { width: 26px; height: 26px; border-radius: 6px; display: flex; align-items: center; justify-content: center; cursor: pointer; }
.cm-trigger:hover, .cm-trigger.open { background: rgba(0, 0, 0, 0.06); }
.cm-mask { position: fixed; inset: 0; z-index: 60; }
.cm-menu { position: absolute; top: 100%; right: 0; z-index: 61; min-width: 160px; padding: 6px; background: var(--surface); border: 1px solid var(--line); border-radius: 12px; box-shadow: 0 8px 24px rgba(0, 0, 0, 0.12); }
.cm-menu.up { top: auto; bottom: 100%; }
.cm-item { display: flex; align-items: center; gap: 10px; padding: 8px 10px; border-radius: 8px; font-size: 14px; color: var(--ink); cursor: pointer; white-space: nowrap; }
.cm-item:hover { background: var(--surface-2); }
.cm-item.danger { color: var(--danger); }
.cm-line { height: 1px; background: var(--line); margin: 4px 6px; }
</style>
