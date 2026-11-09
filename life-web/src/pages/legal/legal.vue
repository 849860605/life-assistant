<template>
  <view class="legal">
    <view class="bar">
      <view class="back" @click="back"><Icon name="back" :size="20" color="var(--ink)" /></view>
      <text class="bar-title">{{ TITLES[doc] }}</text>
      <view class="switch-doc" @click="toggle">{{ TITLES[other] }}</view>
    </view>
    <view class="doc">
      <view class="doc-title">星络{{ TITLES[doc] }}</view>
      <view class="doc-date">更新和生效日期：{{ UPDATED }}</view>
      <MarkdownView :text="text" />
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { api } from "../../api";
import { getToken } from "../../api/http";
import Icon from "../../components/Icon.vue";
import MarkdownView from "../../components/MarkdownView.vue";
import { TITLES, UPDATED, legalText, type LegalDoc } from "../../utils/legal";

/** 用户协议、隐私政策：不用登录也能看，?doc=terms|privacy；联系邮箱从后端取 */
const doc = ref<LegalDoc>("terms");
const email = ref<string | null>(null);
const other = computed<LegalDoc>(() => (doc.value === "terms" ? "privacy" : "terms"));
const text = computed(() => legalText(doc.value, email.value));

onLoad((q) => {
  if (q?.doc === "privacy") doc.value = "privacy";
});
api.contact().then((c) => (email.value = c.email)).catch(() => {});

/** 切到另一份 */
function toggle() {
  doc.value = other.value;
  uni.pageScrollTo({ scrollTop: 0, duration: 0 });
}

/** 从登录页或"我的"点进来的返回上一页；直接打开的回登录页或首页 */
function back() {
  if (getCurrentPages().length > 1) uni.navigateBack();
  else uni.reLaunch({ url: getToken() ? "/pages/chat/chat" : "/pages/login/login" });
}
</script>

<style scoped>
/* 版式：顶栏（返回、标题、切到另一份），下面是一栏居中的正文，宽屏也只占阅读宽度 */
.legal { min-height: 100vh; background: var(--bg); color: var(--ink); }
.bar {
  position: sticky;
  top: env(safe-area-inset-top, 0px);
  z-index: 5;
  display: flex;
  align-items: center;
  gap: 8px;
  height: 52px;
  padding: 0 max(12px, env(safe-area-inset-left)) 0 max(12px, env(safe-area-inset-right));
  background: var(--surface);
  border-bottom: 1px solid var(--line);
}
.back { display: flex; padding: 6px; cursor: pointer; }
.bar-title { flex: 1; font-size: 16px; font-weight: 700; }
.switch-doc { font-size: 14px; color: var(--accent); cursor: pointer; padding: 6px 4px; }
.doc {
  max-width: 760px;
  margin: 0 auto;
  padding: 24px max(20px, env(safe-area-inset-left)) 48px max(20px, env(safe-area-inset-right));
  font-size: 15px;
  line-height: 1.75;
}
.doc-title { font-size: 24px; font-weight: 800; }
.doc-date { margin: 4px 0 16px; font-size: 13px; color: var(--muted); }
</style>

<style>
/* rich-text 里的内容不吃 scoped 样式，这里单独给协议正文的标题和表格排版 */
.legal .md h2 { font-size: 17px; margin: 22px 0 8px; }
.legal .md li { margin: 4px 0; }
.legal .md table { display: block; overflow-x: auto; border-collapse: collapse; margin: 10px 0; font-size: 13px; line-height: 1.6; }
.legal .md th, .legal .md td { border: 1px solid var(--line); padding: 6px 10px; text-align: left; vertical-align: top; min-width: 90px; }
.legal .md th { background: var(--surface-2); font-weight: 700; }
</style>
