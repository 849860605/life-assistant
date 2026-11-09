<template>
  <div class="login">
    <div class="box">
      <div class="brand">
        <img class="logo" src="/brand.svg" alt="" />
        <div>
          <div class="name">星络 <span class="en">Xinglo</span></div>
          <div class="sub">管理后台</div>
        </div>
      </div>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
        <el-form-item label="管理员账号" prop="username">
          <el-input id="admin-username" v-model="form.username" autocomplete="username" size="large" :prefix-icon="User" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input id="admin-password" v-model="form.password" type="password" show-password autocomplete="current-password" size="large" :prefix-icon="Lock" @keyup.enter="submit" />
        </el-form-item>
        <!-- 输错几次以后才出现，点图片换一张 -->
        <el-form-item v-if="captcha" label="验证码">
          <div class="captcha">
            <el-input id="admin-captcha" v-model="captchaCode" maxlength="4" size="large" placeholder="右侧图中的 4 个字符" @keyup.enter="submit" />
            <img :src="captcha.image" alt="验证码" title="看不清？点一下换一张" @click="refreshCaptcha" />
          </div>
        </el-form-item>
        <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="err" />
        <el-button type="primary" size="large" class="go" :loading="loading" native-type="submit">登录</el-button>
      </el-form>
      <div class="note">这里只给管理员用。App 用户请在星络里登录。</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import type { FormInstance, FormRules } from "element-plus";
import { Lock, User } from "@element-plus/icons-vue";
import { api } from "../api";
import { ApiError } from "../api/http";
import { signIn } from "../session";

/** 管理员登录页：登录后回到原来要去的页面（没有就去总览） */
const router = useRouter();
const route = useRoute();
const formRef = ref<FormInstance>();
const form = reactive({ username: "", password: "" });
const rules: FormRules = {
  username: [{ required: true, message: "请输入账号", trigger: "blur" }],
  password: [{ required: true, message: "请输入密码", trigger: "blur" }],
};
const loading = ref(false);
const error = ref("");
/** 输错几次以后要填的图形验证码；没要求时为空 */
const captcha = ref<{ id: string; image: string } | null>(null);
const captchaCode = ref("");

async function submit() {
  if (!(await formRef.value?.validate().catch(() => false))) return;
  if (captcha.value && captchaCode.value.trim().length !== 4) {
    error.value = "请输入图中的 4 个字符";
    return;
  }
  loading.value = true;
  error.value = "";
  try {
    const answer = captcha.value ? { id: captcha.value.id, code: captchaCode.value.trim() } : undefined;
    const r = await api.login(form.username.trim(), form.password, answer);
    signIn(r.token, r.admin);
    const from = typeof route.query.from === "string" ? route.query.from : "/dashboard";
    router.replace(from);
  } catch (e) {
    error.value = e instanceof Error ? e.message : "登录失败";
    // 后端要验证码（428），或者已经填过一张（不管对错都作废了），换一张新的
    if ((e instanceof ApiError && e.code === 428) || captcha.value) refreshCaptcha();
  } finally {
    loading.value = false;
  }
}

/** 换一张验证码，清掉已填的 */
async function refreshCaptcha() {
  captchaCode.value = "";
  try {
    captcha.value = await api.captcha();
  } catch (e) {
    error.value = e instanceof Error ? e.message : "验证码加载失败，点一下重试";
  }
}
</script>

<style scoped>
.login {
  min-height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px 16px;
  box-sizing: border-box;
  background: radial-gradient(1200px 500px at 50% -10%, #e4ecff, transparent), var(--x-bg);
}
.box {
  width: 100%;
  max-width: 380px;
  background: #fff;
  border: 1px solid var(--x-line);
  border-radius: 16px;
  padding: 28px 28px 22px;
  box-shadow: 0 12px 40px rgba(47, 107, 242, 0.08);
}
.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 24px;
}
.logo {
  width: 44px;
  height: 44px;
}
.name {
  font-size: 20px;
  font-weight: 800;
}
.en {
  color: var(--el-color-primary);
  font-size: 16px;
}
.sub {
  color: var(--x-muted);
  font-size: 13px;
}
.captcha {
  display: flex;
  gap: 10px;
  width: 100%;
}
.captcha img {
  flex-shrink: 0;
  width: 110px;
  height: 40px;
  border-radius: 6px;
  border: 1px solid var(--x-line);
  cursor: pointer;
}
.err {
  margin-bottom: 14px;
}
.go {
  width: 100%;
}
.note {
  margin-top: 16px;
  font-size: 12px;
  color: var(--x-muted);
  text-align: center;
}
</style>
