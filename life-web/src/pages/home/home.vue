<template>
  <view />
</template>

<script setup lang="ts">
import { onLoad } from "@dcloudio/uni-app";

/** 旧的首页地址：首页已经和对话合成一页（问星络标签就是对话页），打开时直接转过去，带的参数原样带上 */
onLoad((q) => {
  const query = q ? Object.entries(q).map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`).join("&") : "";
  uni.reLaunch({ url: "/pages/chat/chat" + (query ? "?" + query : "") });
});
</script>
