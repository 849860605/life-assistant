<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">用户管理</h1>
        <div class="page-sub">星络 App 的用户。这里只看账号和用量统计，看不到对话内容和行程内容</div>
      </div>
      <div class="tools">
        <el-input id="user-search" v-model="keyword" placeholder="搜用户名、昵称或邮箱" clearable :prefix-icon="Search" class="search" @keyup.enter="search" @clear="search" />
        <el-button type="primary" :icon="Plus" @click="openCreate">新建用户</el-button>
      </div>
    </div>

    <!-- 注册开关：打开后任何人都能用邮箱注册 -->
    <div class="card register">
      <div>
        <div class="reg-title">开放注册</div>
        <div class="muted small">
          {{ register.open ? "任何人都能在登录页用邮箱收验证码注册" : "已关闭，只能由管理员在这里新建用户" }}
          <span v-if="!register.mailReady" class="warn">；发信邮箱还没配好（服务器 .env 的 LIFE_MAIL_*），现在收不到验证码</span>
        </div>
      </div>
      <el-switch id="register-open" v-model="register.open" :loading="register.saving" :before-change="toggleRegister" />
    </div>

    <div class="card">
      <el-table v-loading="loading" :data="rows" empty-text="没有找到用户" @row-click="openDetail">
        <el-table-column label="用户" min-width="160">
          <template #default="{ row }">
            <div class="who">
              <div class="avatar">{{ (row.nickname || row.username).slice(0, 1) }}</div>
              <div>
                <div class="uname">{{ row.nickname || row.username }}</div>
                <div class="muted small">{{ row.email || "@" + row.username }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="来源" width="90">
          <template #default="{ row }"><el-tag :type="row.source === 'EMAIL' ? 'primary' : 'info'" size="small" effect="plain">{{ sourceText(row.source) }}</el-tag></template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }"><el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">{{ row.status === 1 ? "正常" : "已停用" }}</el-tag></template>
        </el-table-column>
        <el-table-column label="对话" width="70" align="right"><template #default="{ row }"><span class="num">{{ row.conversations }}</span></template></el-table-column>
        <el-table-column label="行程" width="70" align="right"><template #default="{ row }"><span class="num">{{ row.trips }}</span></template></el-table-column>
        <el-table-column label="最近活跃" width="110"><template #default="{ row }">{{ fromNow(row.lastActiveAt) }}</template></el-table-column>
        <el-table-column label="最近登录" width="110"><template #default="{ row }">{{ fromNow(row.lastLoginAt) }}</template></el-table-column>
        <el-table-column label="创建时间" width="150"><template #default="{ row }"><span class="num">{{ fmtTime(row.createdAt) }}</span></template></el-table-column>
        <el-table-column label="操作" width="200" :fixed="narrow ? false : 'right'">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="openDetail(row)">详情</el-button>
            <el-button link type="primary" @click.stop="openReset(row)">重置密码</el-button>
            <el-button link :type="row.status === 1 ? 'danger' : 'success'" @click.stop="toggle(row)">{{ row.status === 1 ? "停用" : "启用" }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <el-pagination v-model:current-page="page" :page-size="size" :total="total" layout="total, prev, pager, next" background @current-change="load" />
      </div>
    </div>

    <!-- 新建用户 -->
    <el-dialog v-model="createOpen" title="新建用户" width="min(440px, 92vw)" @closed="createRef?.resetFields()">
      <el-form ref="createRef" :model="createForm" :rules="createRules" label-width="84px">
        <el-form-item label="用户名" prop="username"><el-input id="new-username" v-model="createForm.username" placeholder="登录用，如 sunxiaoyan" /></el-form-item>
        <el-form-item label="昵称" prop="nickname"><el-input id="new-nickname" v-model="createForm.nickname" placeholder="App 里显示的名字，可不填" /></el-form-item>
        <el-form-item label="初始密码" prop="password">
          <el-input id="new-password" v-model="createForm.password">
            <template #append><el-button @click="createForm.password = randomPassword()">随机生成</el-button></template>
          </el-input>
        </el-form-item>
        <div class="hint">8 位以上，同时有字母和数字。建好后把账号和密码告诉对方，让他登录后自己保管。</div>
      </el-form>
      <template #footer>
        <el-button @click="createOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="create">创建</el-button>
      </template>
    </el-dialog>

    <!-- 重置密码 -->
    <el-dialog v-model="resetOpen" :title="`重置「${resetTarget?.nickname || resetTarget?.username}」的密码`" width="min(440px, 92vw)">
      <el-form ref="resetRef" :model="resetForm" :rules="{ password: passwordRules }" label-width="70px">
        <el-form-item label="新密码" prop="password">
          <el-input id="reset-password" v-model="resetForm.password">
            <template #append><el-button @click="resetForm.password = randomPassword()">随机生成</el-button></template>
          </el-input>
        </el-form-item>
        <div class="hint">重置后这个用户在所有设备上都会退出登录，要用新密码重新登录。</div>
      </el-form>
      <template #footer>
        <el-button @click="resetOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="reset">重置</el-button>
      </template>
    </el-dialog>

    <!-- 详情 -->
    <el-drawer v-model="detailOpen" :title="detail ? detail.nickname || detail.username : '用户详情'" size="min(420px, 92vw)">
      <div v-if="detail" class="detail">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="用户名">{{ detail.username }}</el-descriptions-item>
          <el-descriptions-item label="邮箱">{{ detail.email || "没绑定" }}</el-descriptions-item>
          <el-descriptions-item label="来源">{{ sourceText(detail.source) }}</el-descriptions-item>
          <el-descriptions-item label="状态"><el-tag :type="detail.enabled ? 'success' : 'info'" size="small">{{ detail.enabled ? "正常" : "已停用" }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="时区">{{ detail.timezone }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ fmtTime(detail.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="最近登录">{{ fmtTime(detail.lastLoginAt) }}</el-descriptions-item>
          <el-descriptions-item label="最近活跃">{{ fmtTime(detail.summary.lastActiveAt) }}</el-descriptions-item>
        </el-descriptions>
        <div class="sum-title">使用情况</div>
        <div class="sums">
          <div class="sum"><div class="sum-v num">{{ detail.summary.conversations }}</div><div class="muted small">对话</div></div>
          <div class="sum"><div class="sum-v num">{{ detail.summary.messages }}</div><div class="muted small">提问</div></div>
          <div class="sum"><div class="sum-v num">{{ detail.summary.trips }}</div><div class="muted small">行程</div></div>
          <div class="sum"><div class="sum-v num">{{ detail.summary.events }}</div><div class="muted small">日程</div></div>
          <div class="sum"><div class="sum-v num">{{ detail.summary.memories }}</div><div class="muted small">记忆</div></div>
        </div>
        <div class="sum-title">近 30 天模型用量</div>
        <div class="sums three">
          <div class="sum"><div class="sum-v num">{{ num(detail.summary.llmCalls30d) }}</div><div class="muted small">调用次数</div></div>
          <div class="sum"><div class="sum-v num">{{ num(detail.summary.tokens30d) }}</div><div class="muted small">Token</div></div>
          <div class="sum"><div class="sum-v num">{{ money(detail.summary.cost30d) }}</div><div class="muted small">费用</div></div>
        </div>
        <div class="muted small privacy">出于隐私，后台不提供对话和行程的具体内容。</div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from "element-plus";
import { Plus, Search } from "@element-plus/icons-vue";
import { api, type UserDetail, type UserRow } from "../api";
import { fmtTime, fromNow, money, narrow, num, passwordRules, randomPassword, usernameRules } from "../utils";

/** App 用户管理：注册开关、搜索分页、新建、详情（只有统计）、重置密码、启用停用 */
const keyword = ref("");
const page = ref(1);
const size = 20;
const total = ref(0);
const rows = ref<UserRow[]>([]);
const loading = ref(false);
const saving = ref(false);

async function load() {
  loading.value = true;
  try {
    const r = await api.users({ keyword: keyword.value.trim() || undefined, page: page.value, size });
    rows.value = r.items;
    total.value = r.total;
  } finally {
    loading.value = false;
  }
}

function search() {
  page.value = 1;
  load();
}

/** 账号来源的中文 */
function sourceText(source: string) {
  return source === "EMAIL" ? "自己注册" : "后台新建";
}

// 注册开关
const register = reactive({ open: true, mailReady: true, saving: false });

async function loadRegister() {
  Object.assign(register, await api.registerSetting());
}

/** 切换开关前先确认，关掉后登录页的注册入口提示"暂未开放注册" */
async function toggleRegister(): Promise<boolean> {
  const next = !register.open;
  try {
    await ElMessageBox.confirm(
      next ? "打开后，任何人都能用邮箱注册星络账号，每个人聊天都会产生模型费用。确定打开？" : "关闭后新用户不能自己注册，已有账号不受影响。确定关闭？",
      next ? "开放注册" : "关闭注册",
      { confirmButtonText: next ? "打开" : "关闭", cancelButtonText: "取消", type: next ? "warning" : "info" },
    );
  } catch {
    return false;
  }
  register.saving = true;
  try {
    Object.assign(register, await api.setRegisterOpen(next));
    ElMessage.success(next ? "已开放注册" : "已关闭注册");
    // 开关的值由上面这次返回写好了，不用 el-switch 再翻一次
    return false;
  } finally {
    register.saving = false;
  }
}

// 新建
const createOpen = ref(false);
const createRef = ref<FormInstance>();
const createForm = reactive({ username: "", nickname: "", password: "" });
const createRules: FormRules = { username: usernameRules, password: passwordRules };

function openCreate() {
  createForm.password = randomPassword();
  createOpen.value = true;
}

async function create() {
  if (!(await createRef.value?.validate().catch(() => false))) return;
  saving.value = true;
  try {
    const u = await api.createUser({ username: createForm.username.trim(), password: createForm.password, nickname: createForm.nickname.trim() || undefined });
    const pwd = createForm.password;
    createOpen.value = false;
    await ElMessageBox.alert(`账号：${u.username}\n初始密码：${pwd}\n\n这个密码只显示这一次，请现在发给对方。`, "用户已创建", {
      confirmButtonText: "我记下了",
      customStyle: { whiteSpace: "pre-line" },
    }).catch(() => undefined);
    load();
  } finally {
    saving.value = false;
  }
}

// 重置密码
const resetOpen = ref(false);
const resetRef = ref<FormInstance>();
const resetTarget = ref<UserRow | null>(null);
const resetForm = reactive({ password: "" });

function openReset(row: UserRow) {
  resetTarget.value = row;
  resetForm.password = randomPassword();
  resetOpen.value = true;
}

async function reset() {
  if (!resetTarget.value || !(await resetRef.value?.validate().catch(() => false))) return;
  saving.value = true;
  try {
    await api.resetUserPassword(resetTarget.value.id, resetForm.password);
    resetOpen.value = false;
    await ElMessageBox.alert(`账号：${resetTarget.value.username}\n新密码：${resetForm.password}\n\n这个密码只显示这一次。`, "密码已重置", {
      confirmButtonText: "我记下了",
      customStyle: { whiteSpace: "pre-line" },
    }).catch(() => undefined);
  } finally {
    saving.value = false;
  }
}

// 启用停用
async function toggle(row: UserRow) {
  const disabling = row.status === 1;
  const ok = await ElMessageBox.confirm(
    disabling ? `停用后「${row.nickname || row.username}」不能再登录，已登录的设备会立刻退出。数据都保留，之后可以再启用。` : `启用后「${row.nickname || row.username}」可以重新登录。`,
    disabling ? "停用用户" : "启用用户",
    { type: disabling ? "warning" : "info", confirmButtonText: disabling ? "停用" : "启用", cancelButtonText: "取消" },
  ).then(() => true, () => false);
  if (!ok) return;
  await api.setUserEnabled(row.id, !disabling);
  ElMessage.success(disabling ? "已停用" : "已启用");
  load();
}

// 详情
const detailOpen = ref(false);
const detail = ref<UserDetail | null>(null);
async function openDetail(row: UserRow) {
  detail.value = null;
  detailOpen.value = true;
  detail.value = await api.user(row.id);
}

onMounted(() => {
  load();
  loadRegister().catch(() => {});
});
</script>

<style scoped>
.register { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.reg-title { font-weight: 700; margin-bottom: 2px; }
.warn { color: var(--el-color-warning); }
.tools {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}
.search {
  width: 220px;
}
.who {
  display: flex;
  align-items: center;
  gap: 10px;
}
.avatar {
  width: 32px;
  height: 32px;
  border-radius: 10px;
  background: var(--x-tile);
  color: var(--el-color-primary);
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: inset 0 0 0 1px rgba(47, 107, 242, 0.15);
  flex-shrink: 0;
}
.uname {
  font-weight: 600;
}
.small {
  font-size: 12px;
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}
.hint {
  font-size: 12px;
  color: var(--x-muted);
  line-height: 1.6;
  padding-left: 84px;
}
.sum-title {
  font-weight: 700;
  margin: 20px 0 10px;
}
.sums {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 8px;
}
.sums.three {
  grid-template-columns: repeat(3, 1fr);
}
.sum {
  background: var(--x-bg);
  border-radius: 10px;
  padding: 10px 6px;
  text-align: center;
}
.sum-v {
  font-size: 18px;
  font-weight: 700;
}
.privacy {
  margin-top: 18px;
}
:deep(.el-table__row) {
  cursor: pointer;
}
@media (max-width: 600px) {
  .search {
    width: 100%;
  }
  .hint {
    padding-left: 0;
  }
}
</style>
