<template>
  <div class="wrap">
    <div class="page-head">
      <div>
        <h1 class="page-title">修改密码</h1>
        <div class="page-sub">改的是你自己（{{ session.admin?.username }}）的后台登录密码</div>
      </div>
    </div>
    <div class="card">
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
        <el-form-item label="原密码" prop="oldPassword">
          <el-input id="old-password" v-model="form.oldPassword" type="password" show-password autocomplete="current-password" />
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <el-input id="new-password" v-model="form.newPassword" type="password" show-password autocomplete="new-password" />
        </el-form-item>
        <el-form-item label="再输一次新密码" prop="confirm">
          <el-input id="confirm-password" v-model="form.confirm" type="password" show-password autocomplete="new-password" />
        </el-form-item>
        <div class="hint">8 位以上，同时有字母和数字。改完后，你在其他地方登录的后台会退出，当前页面不受影响。</div>
        <el-button type="primary" :loading="saving" native-type="submit">保存新密码</el-button>
      </el-form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from "vue";
import { ElMessage, type FormInstance, type FormRules } from "element-plus";
import { api } from "../api";
import { session } from "../session";
import { passwordRules } from "../utils";

/** 修改自己的密码：要输原密码，新密码输两遍 */
const formRef = ref<FormInstance>();
const form = reactive({ oldPassword: "", newPassword: "", confirm: "" });
const saving = ref(false);
const rules: FormRules = {
  oldPassword: [{ required: true, message: "请输入原密码", trigger: "blur" }],
  newPassword: passwordRules,
  confirm: [
    { required: true, message: "请再输一次新密码", trigger: "blur" },
    { validator: (_r, v: string, cb) => (v === form.newPassword ? cb() : cb(new Error("两次输入的新密码不一样"))), trigger: "blur" },
  ],
};

async function submit() {
  if (!(await formRef.value?.validate().catch(() => false))) return;
  saving.value = true;
  try {
    await api.changePassword(form.oldPassword, form.newPassword);
    ElMessage.success("密码已修改");
    formRef.value?.resetFields();
  } finally {
    saving.value = false;
  }
}
</script>

<style scoped>
.wrap {
  max-width: 480px;
}
.hint {
  font-size: 12px;
  color: var(--x-muted);
  margin-bottom: 16px;
  line-height: 1.6;
}
</style>
