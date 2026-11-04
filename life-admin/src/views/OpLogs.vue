<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">操作日志</h1>
        <div class="page-sub">管理员在后台做的每个改动都记在这里，按时间倒序</div>
      </div>
      <el-select v-model="action" placeholder="全部操作" clearable class="filter" @change="search">
        <el-option v-for="a in actions" :key="a" :label="actionLabel(a)" :value="a" />
      </el-select>
    </div>
    <div class="card">
      <el-table v-loading="loading" :data="rows" empty-text="还没有操作记录">
        <el-table-column label="时间" width="160"><template #default="{ row }"><span class="num">{{ fmtTime(row.createdAt) }}</span></template></el-table-column>
        <el-table-column label="管理员" width="140"><template #default="{ row }">{{ row.adminName || `#${row.adminId}` }}</template></el-table-column>
        <el-table-column label="操作" width="160">
          <template #default="{ row }"><el-tag size="small" :type="tone(row.action)">{{ actionLabel(row.action) }}</el-tag></template>
        </el-table-column>
        <el-table-column label="对象" min-width="160"><template #default="{ row }">{{ row.target || "—" }}</template></el-table-column>
        <el-table-column label="IP" width="140"><template #default="{ row }"><span class="num muted">{{ row.ip || "—" }}</span></template></el-table-column>
      </el-table>
      <div class="pager">
        <el-pagination v-model:current-page="page" :page-size="size" :total="total" layout="total, prev, pager, next" background @current-change="load" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { api, type OpLog } from "../api";
import { actionLabel, fmtTime } from "../utils";

/** 操作日志：按操作类型筛选、分页 */
const rows = ref<OpLog[]>([]);
const actions = ref<string[]>([]);
const action = ref("");
const page = ref(1);
const size = 20;
const total = ref(0);
const loading = ref(false);

async function load() {
  loading.value = true;
  try {
    const r = await api.opLogs({ action: action.value || undefined, page: page.value, size });
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

/** 停用、重置类的标成橙色，登录灰色，其余蓝色 */
function tone(a: string): "warning" | "info" | "primary" {
  if (a.startsWith("DISABLE") || a.startsWith("RESET")) return "warning";
  if (a === "LOGIN") return "info";
  return "primary";
}

onMounted(() => {
  load();
  api.opActions().then((a) => (actions.value = a)).catch(() => undefined);
});
</script>

<style scoped>
.filter {
  width: 180px;
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}
</style>
