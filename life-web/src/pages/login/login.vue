<template>
  <view class="login">
    <view class="panel">
      <view class="brand">生活助手</view>
      <view class="tagline">出游规划、日程提醒，以后还有健身和记账</view>
      <view class="form">
        <input id="username" v-model="username" class="input" placeholder="账号" />
        <input id="password" v-model="password" class="input" password placeholder="密码" @confirm="submit" />
        <button class="btn" :disabled="loading || !username || !password" @click="submit">
          {{ loading ? "登录中…" : "登录" }}
        </button>
        <view v-if="error" class="error">{{ error }}</view>
      </view>
      <view class="hint">账号和密码在项目根目录的 .env 里（LIFE_USERNAME / LIFE_PASSWORD）</view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { api } from "../../api";
import { setToken } from "../../api/http";
import { clearMediaTicket } from "../../utils/media";

const username = ref("");
const password = ref("");
const loading = ref(false);
const error = ref("");

/** 登录成功后保存 token，进入对话页。 */
async function submit() {
  if (!username.value || !password.value || loading.value) return;
  loading.value = true;
  error.value = "";
  try {
    const result = await api.login(username.value.trim(), password.value);
    setToken(result.token);
    // 换了账号，旧的下载票据不能再用
    clearMediaTicket();
    uni.switchTab({ url: "/pages/home/home" });
  } catch (e) {
    error.value = e instanceof Error ? e.message : "登录失败";
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped>
.login {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px max(16px, env(safe-area-inset-left)) 24px max(16px, env(safe-area-inset-right));
  box-sizing: border-box;
}
.panel { width: 100%; max-width: 360px; }
.brand { font-size: 28px; font-weight: 600; letter-spacing: 0.02em; }
.tagline { color: var(--muted); margin: 6px 0 28px; font-size: 14px; }
.form { display: flex; flex-direction: column; gap: 12px; }
.form .input { width: 100%; }
.form .btn { width: 100%; margin: 4px 0 0; }
.error { color: var(--danger); font-size: 14px; }
.hint { margin-top: 28px; font-size: 12px; color: var(--muted); }
</style>
