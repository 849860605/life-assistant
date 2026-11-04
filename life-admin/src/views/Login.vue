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

async function submit() {
  if (!(await formRef.value?.validate().catch(() => false))) return;
  loading.value = true;
  error.value = "";
  try {
    const r = await api.login(form.username.trim(), form.password);
    signIn(r.token, r.admin);
    const from = typeof route.query.from === "string" ? route.query.from : "/dashboard";
    router.replace(from);
  } catch (e) {
    error.value = e instanceof Error ? e.message : "登录失败";
  } finally {
    loading.value = false;
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
