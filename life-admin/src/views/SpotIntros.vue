<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">景点简介</h1>
        <div class="page-sub">用户在行程里打开景点详情时，AI 根据飞猪和网上资料整理的两三句简介，所有用户共用。写错了在这里改，改过的不会被自动覆盖；删掉的下次有人打开时重新生成。</div>
      </div>
      <el-input v-model="keyword" placeholder="搜景点名或城市" clearable class="filter" @keyup.enter="load" @clear="load" />
    </div>
    <div class="card">
      <el-table v-loading="loading" :data="rows" empty-text="还没有景点简介">
        <el-table-column label="景点" width="180">
          <template #default="{ row }"><div class="name">{{ row.name }}</div><div class="muted">{{ row.city || "—" }}</div></template>
        </el-table-column>
        <el-table-column label="简介" min-width="360">
          <template #default="{ row }"><span v-if="row.intro">{{ row.intro }}</span><span v-else class="muted">资料太少，没写出来（不显示简介）</span></template>
        </el-table-column>
        <el-table-column label="来源" width="130">
          <template #default="{ row }"><el-tag size="small" :type="row.manual ? 'success' : 'info'">{{ row.manual ? "人工" : row.sources || "—" }}</el-tag></template>
        </el-table-column>
        <el-table-column label="更新时间" width="150"><template #default="{ row }"><span class="num">{{ fmtTime(row.updatedAt) }}</span></template></el-table-column>
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <el-button link type="primary" @click="startEdit(row)">修改</el-button>
            <el-button link type="danger" @click="regenerate(row)">重新生成</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="editing" :title="`修改「${current?.name}」的简介`" width="560px">
      <el-input v-model="text" type="textarea" :rows="5" maxlength="400" show-word-limit placeholder="留空表示这个景点不显示简介" />
      <div class="muted hint">只写确定的信息；开放时间、门票不用写，详情里会单独查。</div>
      <template #footer>
        <el-button @click="editing = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { api, type SpotIntroRow } from "../api";
import { fmtTime } from "../utils";

/** 景点简介：按名称搜、改简介、删掉重新生成 */
const rows = ref<SpotIntroRow[]>([]);
const keyword = ref("");
const loading = ref(false);
const editing = ref(false);
const saving = ref(false);
const current = ref<SpotIntroRow | null>(null);
const text = ref("");

async function load() {
  loading.value = true;
  try {
    rows.value = await api.spotIntros(keyword.value.trim() || undefined);
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    loading.value = false;
  }
}

function startEdit(row: SpotIntroRow) {
  current.value = row;
  text.value = row.intro || "";
  editing.value = true;
}

async function save() {
  if (!current.value) return;
  saving.value = true;
  try {
    await api.editSpotIntro(current.value.id, text.value);
    editing.value = false;
    ElMessage.success("已保存，之后不会被自动覆盖");
    await load();
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    saving.value = false;
  }
}

async function regenerate(row: SpotIntroRow) {
  try {
    await ElMessageBox.confirm(`删掉「${row.name}」现在的简介，下次有人打开这个景点时按资料重新生成？`, "重新生成", { type: "warning" });
  } catch {
    return;
  }
  try {
    await api.deleteSpotIntro(row.id);
    ElMessage.success("已删掉，下次打开时重新生成");
    await load();
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}

onMounted(load);
</script>

<style scoped>
.filter { width: 240px; }
.name { font-weight: 600; }
.muted { color: var(--el-text-color-secondary); font-size: 12px; }
.hint { margin-top: 8px; }
</style>
