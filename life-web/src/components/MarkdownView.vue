<template>
  <view class="md"><rich-text :nodes="html" /></view>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { renderMarkdown } from "../utils/format";

/** 渲染 Markdown 文本（已做 XSS 过滤）。 */
const props = defineProps<{ text: string }>();
const html = computed(() => renderMarkdown(props.text));
</script>
