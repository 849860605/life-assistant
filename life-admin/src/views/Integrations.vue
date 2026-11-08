<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">第三方平台</h1>
        <div class="page-sub">改完立即生效，不用重启；没在这里填的项继续用服务器 .env 里的值<span v-if="!isSuper()">。只有超级管理员能修改</span></div>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
    </div>

    <!-- 规划行程时快速查价用哪家：对话里只查这一家，其他平台保存后后台补 -->
    <section v-if="quick" class="card quick">
      <div class="quick-head">
        <h2 class="group-title">快速查价</h2>
        <span class="muted small">让星络规划行程时，机票和酒店只查一家拿参考价，几秒出方案；其他平台的价格在行程保存后由后台补上，用户在行程里也能点"比价"</span>
      </div>
      <div class="quick-row">
        <label class="quick-label" for="quick-flight">机票</label>
        <el-select id="quick-flight" :model-value="quick.flight" :disabled="!isSuper() || quickBusy" style="width: 200px" @change="(v: string) => saveQuick({ flight: v })">
          <el-option v-for="o in quick.flightOptions" :key="o.platform" :value="o.platform" :label="o.platform + (o.available ? '' : '（未配置）')" :disabled="!o.available" />
        </el-select>
        <span class="muted small">按航线只查这一家，挑一班写进行程</span>
      </div>
      <div class="quick-row">
        <label class="quick-label" for="quick-hotel">酒店参考价</label>
        <el-select id="quick-hotel" :model-value="quick.hotel" :disabled="!isSuper() || quickBusy" style="width: 200px" @change="(v: string) => saveQuick({ hotel: v })">
          <el-option v-for="o in quick.hotelOptions" :key="o.platform" :value="o.platform" :label="o.platform + (o.available ? '' : '（未配置）')" :disabled="!o.available" />
        </el-select>
        <span class="muted small">候选酒店由 RollingGo 按片区搜；选别的平台时，再用它给前三家报价</span>
      </div>
    </section>

    <div v-loading="loading && !list.length" class="groups">
      <section v-for="g in groups" :key="g.category" class="group">
        <h2 class="group-title">{{ g.category }}</h2>
        <div class="grid">
          <div v-for="it in g.items" :key="it.code" class="card plat" :class="{ off: it.status === 'OFF' }">
            <div class="plat-head">
              <div class="plat-name">
                <span>{{ it.name }}</span>
                <el-tag size="small" :type="STATUS[it.status].type" effect="light">{{ STATUS[it.status].text }}</el-tag>
              </div>
              <el-switch
                v-if="isSuper()"
                :id="`switch-${it.code}`"
                :model-value="it.enabled"
                :loading="busy === it.code"
                :aria-label="`${it.enabled ? '停用' : '启用'}${it.name}`"
                @change="(v: string | number | boolean) => toggle(it, Boolean(v))"
              />
            </div>
            <div class="desc muted">{{ it.description }}</div>

            <dl v-if="it.fields.length" class="fields">
              <template v-for="f in it.fields" :key="f.name">
                <dt>{{ f.label }}</dt>
                <dd>
                  <span class="val num" :class="{ empty: !shown(f) }">{{ shown(f) || "未填" }}</span>
                  <el-tag v-if="f.source" size="small" :type="f.source === 'ADMIN' ? 'primary' : 'info'" effect="plain" class="src">
                    {{ f.source === "ADMIN" ? "后台" : ".env" }}
                  </el-tag>
                </dd>
              </template>
            </dl>
            <div v-else class="fields-note muted small">没有单独的配置项，用的是高德 Web 服务的 Key</div>

            <div class="foot">
              <span class="stat muted small num">{{ statText(it) }}</span>
              <div v-if="isSuper()" class="acts">
                <el-button v-if="it.fields.some((f) => f.kind !== 'READONLY')" link type="primary" @click="openEdit(it)">编辑</el-button>
                <el-button link type="primary" :loading="testing === it.code" :disabled="!it.enabled" @click="test(it)">测试</el-button>
              </div>
            </div>
            <div v-if="results[it.code]" class="result" :class="results[it.code].ok ? 'ok' : 'bad'" role="status">
              {{ results[it.code].ok ? "测试通过" : "测试失败" }} · {{ (results[it.code].ms / 1000).toFixed(1) }} 秒 · {{ results[it.code].message }}
            </div>
          </div>
        </div>
      </section>
    </div>

    <el-drawer v-model="editOpen" :title="`编辑「${editing?.name}」`" :size="narrow ? '100%' : '440px'">
      <template v-if="editing">
        <p class="muted small">{{ editing.description }}</p>
        <el-form label-position="top" @submit.prevent>
          <el-form-item v-for="f in editing.fields" :key="f.name" :label="f.label">
            <el-input
              v-if="f.kind === 'SECRET'"
              :id="`edit-${editing.code}-${f.name}`"
              v-model="form[f.name]"
              type="password"
              show-password
              autocomplete="new-password"
              :placeholder="f.masked ? `留空不改（现在是 ${f.masked}）` : '还没填'"
            />
            <el-input
              v-else
              :id="`edit-${editing.code}-${f.name}`"
              v-model="form[f.name]"
              :disabled="f.kind === 'READONLY'"
              :placeholder="f.placeholder || ''"
            />
            <div class="field-hint muted small">
              <template v-if="f.kind === 'READONLY'">只能在服务器 .env 的 {{ f.env }} 里改（这是在服务器上执行的命令，不开放给后台）</template>
              <template v-else-if="f.source === 'ADMIN'">
                现在用的是后台填的值<template v-if="f.envValue">；
                  <el-checkbox v-model="clear[f.name]" size="small">改回用 .env 的值</el-checkbox>
                </template>
              </template>
              <template v-else-if="f.source === 'ENV'">现在用的是 .env<template v-if="f.env"> 的 {{ f.env }}</template>，填了就以这里为准</template>
              <template v-else>还没配置<template v-if="f.env">，也可以写在 .env 的 {{ f.env }}</template></template>
            </div>
          </el-form-item>
        </el-form>
        <div v-if="editing.fields.some((f) => f.kind === 'TEXT') && editing.fields.some((f) => f.kind === 'SECRET')" class="hint">
          Key 会发到这里填的地址，改了地址要同时重新填 Key。
        </div>
      </template>
      <template #footer>
        <el-button @click="editOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { Refresh } from "@element-plus/icons-vue";
import { api, type Integration, type IntegrationField, type IntegrationTest, type QuickPrice } from "../api";
import { isSuper } from "../session";
import { narrow } from "../utils";

/**
 * 第三方平台：按分类列出酒店机票、地图、天气、搜索、推送各平台的状态、配置来源和近 7 天调用；
 * 超级管理员能改 Key 和地址、启用停用、测试。Key 只显示后 4 位，编辑时留空表示不改。
 */
const list = ref<Integration[]>([]);
/** 快速查价设置 */
const quick = ref<QuickPrice | null>(null);
const quickBusy = ref(false);

async function saveQuick(body: { flight?: string; hotel?: string }) {
  quickBusy.value = true;
  try {
    quick.value = await api.setQuickPrice(body);
    ElMessage.success("已保存，下一次规划就按这个查");
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e));
  } finally {
    quickBusy.value = false;
  }
}
const loading = ref(false);
const busy = ref("");
const testing = ref("");
const results = reactive<Record<string, IntegrationTest>>({});

const STATUS = {
  ON: { text: "已启用", type: "success" },
  UNSET: { text: "缺少配置", type: "warning" },
  OFF: { text: "已停用", type: "info" },
} as const;

const groups = computed(() => {
  const out: { category: string; items: Integration[] }[] = [];
  for (const it of list.value) {
    let g = out.find((x) => x.category === it.category);
    if (!g) out.push((g = { category: it.category, items: [] }));
    g.items.push(it);
  }
  return out;
});

async function load() {
  api.quickPrice().then((q) => (quick.value = q)).catch(() => {});
  loading.value = true;
  try {
    list.value = await api.integrations();
  } finally {
    loading.value = false;
  }
}

function replace(it: Integration) {
  list.value = list.value.map((x) => (x.code === it.code ? it : x));
}

/** 字段在卡片上显示的值：Key 显示遮住的，地址显示原文 */
function shown(f: IntegrationField): string {
  return (f.kind === "SECRET" ? f.masked : f.value) || "";
}

function statText(it: Integration): string {
  if (it.calls7d == null) return "不记调用日志";
  if (!it.calls7d) return "近 7 天没有调用";
  const ok = Math.round(((it.calls7d - (it.failures7d || 0)) * 1000) / it.calls7d) / 10;
  return `近 7 天 ${it.calls7d} 次 · 成功率 ${ok}%${it.avgMs != null ? ` · 平均 ${(it.avgMs / 1000).toFixed(1)} 秒` : ""}`;
}

async function toggle(it: Integration, on: boolean) {
  if (!on) {
    const ok = await ElMessageBox.confirm(`停用后：${it.impact}。停用立即生效，随时可以再打开。`, `停用「${it.name}」？`, {
      confirmButtonText: "停用",
      cancelButtonText: "取消",
      type: it.code === "amap" ? "error" : "warning",
    }).catch(() => false);
    if (!ok) return;
  }
  busy.value = it.code;
  try {
    replace(await api.setIntegrationEnabled(it.code, on));
    delete results[it.code];
    ElMessage.success(on ? `已启用${it.name}` : `已停用${it.name}`);
  } finally {
    busy.value = "";
  }
}

async function test(it: Integration) {
  if (it.testNote && /额度|计费/.test(it.testNote)) {
    const ok = await ElMessageBox.confirm(`测试${it.testNote}，确定要测吗？`, `测试「${it.name}」`, {
      confirmButtonText: "测试",
      cancelButtonText: "取消",
    }).catch(() => false);
    if (!ok) return;
  }
  testing.value = it.code;
  try {
    results[it.code] = await api.testIntegration(it.code);
  } finally {
    testing.value = "";
  }
}

const editOpen = ref(false);
const editing = ref<Integration | null>(null);
const saving = ref(false);
const form = reactive<Record<string, string>>({});
const clear = reactive<Record<string, boolean>>({});

function openEdit(it: Integration) {
  editing.value = it;
  for (const k of Object.keys(form)) delete form[k];
  for (const k of Object.keys(clear)) delete clear[k];
  for (const f of it.fields) form[f.name] = f.kind === "SECRET" ? "" : f.value || "";
  editOpen.value = true;
}

async function save() {
  const it = editing.value;
  if (!it) return;
  const values: Record<string, string> = {};
  for (const f of it.fields) {
    const v = (form[f.name] || "").trim();
    // 地址没改就不传，免得把 .env 的值抄进后台
    if (f.kind === "SECRET" ? v : f.kind === "TEXT" && v && v !== (f.value || "")) values[f.name] = v;
  }
  const cleared = Object.keys(clear).filter((k) => clear[k]);
  if (!Object.keys(values).length && !cleared.length) {
    editOpen.value = false;
    return;
  }
  saving.value = true;
  try {
    replace(await api.updateIntegration(it.code, values, cleared));
    delete results[it.code];
    editOpen.value = false;
    ElMessage.success("已保存，立即生效");
  } finally {
    saving.value = false;
  }
}

onMounted(load);
</script>

<style scoped>
.groups {
  display: flex;
  flex-direction: column;
  gap: 20px;
  min-height: 120px;
}
.group-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--x-muted);
  margin: 0 0 10px;
  letter-spacing: 0.04em;
}
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(340px, 100%), 1fr));
  gap: 12px;
}
.plat {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.plat.off .fields,
.plat.off .desc {
  opacity: 0.6;
}
.plat-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.plat-name {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
}
.desc {
  font-size: 13px;
  line-height: 1.55;
}
.fields {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 6px 12px;
  margin: 0;
  font-size: 13px;
}
.fields dt {
  color: var(--x-muted);
}
.fields dd {
  margin: 0;
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.val {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.val.empty {
  color: var(--x-muted);
}
.src {
  flex-shrink: 0;
}
.fields-note {
  font-size: 12px;
}
.foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: auto;
  padding-top: 8px;
  border-top: 1px solid var(--x-line);
}
.acts {
  display: flex;
  gap: 4px;
}
.small {
  font-size: 12px;
}
.result {
  font-size: 12px;
  line-height: 1.5;
  padding: 6px 10px;
  border-radius: 8px;
  word-break: break-all;
}
.result.ok {
  background: var(--el-color-success-light-9);
  color: var(--el-color-success-dark-2);
}
.result.bad {
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
}
.field-hint {
  font-size: 12px;
  line-height: 1.5;
  margin-top: 4px;
}
.hint {
  font-size: 12px;
  color: var(--x-muted);
  background: var(--x-bg);
  padding: 8px 10px;
  border-radius: 8px;
}
.quick { padding: 16px 20px; margin-bottom: 20px; display: flex; flex-direction: column; gap: 12px; }
.quick-head { display: flex; flex-direction: column; gap: 4px; }
.quick-head .group-title { margin: 0; }
.quick-row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.quick-label { width: 80px; font-size: 14px; }
</style>
