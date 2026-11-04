<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">管理员</h1>
        <div class="page-sub">超级管理员能管理管理员；普通管理员能管理用户、看总览和日志</div>
      </div>
      <el-button type="primary" :icon="Plus" @click="openCreate">新建管理员</el-button>
    </div>

    <div class="card">
      <el-table v-loading="loading" :data="admins" empty-text="还没有管理员">
        <el-table-column label="账号" min-width="160">
          <template #default="{ row }">
            <span class="uname">{{ row.nickname || row.username }}</span>
            <span class="muted small"> @{{ row.username }}</span>
            <el-tag v-if="row.id === session.admin?.id" size="small" class="me">我</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="角色" width="120">
          <template #default="{ row }"><el-tag :type="row.role === 'SUPER' ? 'primary' : 'info'" size="small">{{ row.role === "SUPER" ? "超级管理员" : "管理员" }}</el-tag></template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? "正常" : "已停用" }}</el-tag></template>
        </el-table-column>
        <el-table-column label="最近登录" width="160"><template #default="{ row }"><span class="num">{{ fmtTime(row.lastLoginAt) }}</span></template></el-table-column>
        <el-table-column label="创建时间" width="160"><template #default="{ row }"><span class="num">{{ fmtTime(row.createdAt) }}</span></template></el-table-column>
        <el-table-column label="操作" width="170" :fixed="narrow ? false : 'right'">
          <template #default="{ row }">
            <template v-if="row.id !== session.admin?.id">
              <el-button link type="primary" @click="openReset(row)">重置密码</el-button>
              <el-button link :type="row.enabled ? 'danger' : 'success'" @click="toggle(row)">{{ row.enabled ? "停用" : "启用" }}</el-button>
            </template>
            <el-button v-else link type="primary" @click="router.push('/password')">修改我的密码</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="createOpen" title="新建管理员" width="min(440px, 92vw)" @closed="createRef?.resetFields()">
      <el-form ref="createRef" :model="form" :rules="rules" label-width="84px">
        <el-form-item label="账号" prop="username"><el-input id="new-admin-username" v-model="form.username" /></el-form-item>
        <el-form-item label="称呼" prop="nickname"><el-input id="new-admin-nickname" v-model="form.nickname" placeholder="可不填" /></el-form-item>
        <el-form-item label="角色" prop="role">
          <el-radio-group v-model="form.role">
            <el-radio-button value="ADMIN">管理员</el-radio-button>
            <el-radio-button value="SUPER">超级管理员</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="初始密码" prop="password">
          <el-input id="new-admin-password" v-model="form.password">
            <template #append><el-button @click="form.password = randomPassword()">随机生成</el-button></template>
          </el-input>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="create">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="resetOpen" :title="`重置「${target?.nickname || target?.username}」的密码`" width="min(440px, 92vw)">
      <el-form ref="resetRef" :model="resetForm" :rules="{ password: passwordRules }" label-width="70px">
        <el-form-item label="新密码" prop="password">
          <el-input id="reset-admin-password" v-model="resetForm.password">
            <template #append><el-button @click="resetForm.password = randomPassword()">随机生成</el-button></template>
          </el-input>
        </el-form-item>
        <div class="hint">重置后对方所有已登录的后台页面都会退出。</div>
      </el-form>
      <template #footer>
        <el-button @click="resetOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="reset">重置</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from "element-plus";
import { Plus } from "@element-plus/icons-vue";
import { api, type Admin } from "../api";
import { session } from "../session";
import { fmtTime, narrow, passwordRules, randomPassword, usernameRules } from "../utils";

/** 管理员管理（只有超级管理员能进）：新建、重置别人的密码、启用停用；自己的密码去"修改密码"页改 */
const router = useRouter();
const admins = ref<Admin[]>([]);
const loading = ref(false);
const saving = ref(false);

async function load() {
  loading.value = true;
  try {
    admins.value = await api.admins();
  } finally {
    loading.value = false;
  }
}

const createOpen = ref(false);
const createRef = ref<FormInstance>();
const form = reactive({ username: "", nickname: "", role: "ADMIN", password: "" });
const rules: FormRules = { username: usernameRules, password: passwordRules };

function openCreate() {
  form.password = randomPassword();
  createOpen.value = true;
}

async function create() {
  if (!(await createRef.value?.validate().catch(() => false))) return;
  saving.value = true;
  try {
    const a = await api.createAdmin({ username: form.username.trim(), password: form.password, role: form.role, nickname: form.nickname.trim() || undefined });
    const pwd = form.password;
    createOpen.value = false;
    await ElMessageBox.alert(`账号：${a.username}\n初始密码：${pwd}\n\n这个密码只显示这一次，请让对方登录后在"修改密码"里改掉。`, "管理员已创建", {
      confirmButtonText: "我记下了",
      customStyle: { whiteSpace: "pre-line" },
    }).catch(() => undefined);
    load();
  } finally {
    saving.value = false;
  }
}

const resetOpen = ref(false);
const resetRef = ref<FormInstance>();
const target = ref<Admin | null>(null);
const resetForm = reactive({ password: "" });

function openReset(a: Admin) {
  target.value = a;
  resetForm.password = randomPassword();
  resetOpen.value = true;
}

async function reset() {
  if (!target.value || !(await resetRef.value?.validate().catch(() => false))) return;
  saving.value = true;
  try {
    await api.resetAdminPassword(target.value.id, resetForm.password);
    resetOpen.value = false;
    await ElMessageBox.alert(`账号：${target.value.username}\n新密码：${resetForm.password}\n\n这个密码只显示这一次。`, "密码已重置", {
      confirmButtonText: "我记下了",
      customStyle: { whiteSpace: "pre-line" },
    }).catch(() => undefined);
  } finally {
    saving.value = false;
  }
}

async function toggle(a: Admin) {
  const disabling = a.enabled;
  const ok = await ElMessageBox.confirm(
    disabling ? `停用后「${a.nickname || a.username}」不能再登录后台，已登录的页面会立刻退出。` : `启用后「${a.nickname || a.username}」可以重新登录后台。`,
    disabling ? "停用管理员" : "启用管理员",
    { type: disabling ? "warning" : "info", confirmButtonText: disabling ? "停用" : "启用", cancelButtonText: "取消" },
  ).then(() => true, () => false);
  if (!ok) return;
  await api.setAdminEnabled(a.id, !disabling);
  ElMessage.success(disabling ? "已停用" : "已启用");
  load();
}

onMounted(load);
</script>

<style scoped>
.uname {
  font-weight: 600;
}
.small {
  font-size: 12px;
}
.me {
  margin-left: 6px;
}
.hint {
  font-size: 12px;
  color: var(--x-muted);
  padding-left: 70px;
}
</style>
