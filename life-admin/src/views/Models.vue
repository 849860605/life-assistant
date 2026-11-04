<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">模型配置</h1>
        <div class="page-sub">接入哪些模型、各项任务用哪个、App 里用户能选哪些。改完立即生效，不用重启{{ canEdit ? "" : "（只有超级管理员能改）" }}</div>
      </div>
    </div>

    <!-- 用途分配 -->
    <div class="card section">
      <div class="sec-head"><h2>用途分配</h2><span class="muted small">用户没自己选模型时，对话用这里的"对话"模型</span></div>
      <div class="roles">
        <div v-for="r in ROLE_INFO" :key="r.role" class="role">
          <div class="role-name">{{ r.label }}</div>
          <div class="muted small">{{ r.hint }}</div>
          <el-select
            :id="`role-${r.role}`"
            :model-value="roles[r.role] ?? undefined"
            :disabled="!canEdit"
            placeholder="用 application.yml 里的"
            filterable
            class="role-select"
            @change="(v: number) => assign(r.role, v)"
          >
            <el-option v-for="m in roleOptions(r.role)" :key="m.id" :label="`${m.displayName}（${providerName(m.providerCode)}）`" :value="m.id" />
          </el-select>
        </div>
      </div>
    </div>

    <!-- 提供方 -->
    <div class="card section">
      <div class="sec-head">
        <h2>提供方</h2>
        <span class="muted small">一个提供方是一个接口地址加一个 Key，Key 加密保存，页面上只显示后 4 位</span>
        <el-button v-if="canEdit" type="primary" :icon="Plus" class="push" @click="openProvider()">接入提供方</el-button>
      </div>
      <el-table v-loading="loading" :data="providers" empty-text="还没有提供方">
        <el-table-column label="名称" min-width="150">
          <template #default="{ row }">
            <span class="strong">{{ row.name }}</span><span class="muted small code">{{ row.code }}</span>
          </template>
        </el-table-column>
        <el-table-column label="接口" min-width="240">
          <template #default="{ row }">
            <span v-if="row.type === 'dashscope'" class="small">百炼原生接口</span>
            <span v-else class="mono small">{{ row.baseUrl }}{{ row.completionsPath || "/v1/chat/completions" }}</span>
          </template>
        </el-table-column>
        <el-table-column label="Key" width="150">
          <template #default="{ row }">
            <span v-if="row.hasKey" class="mono small">{{ row.keyTail }}</span>
            <el-tag v-else type="warning" size="small">没填</el-tag>
            <span v-if="row.authHeader" class="muted small"> · 头 {{ row.authHeader }}</span>
          </template>
        </el-table-column>
        <el-table-column label="模型" width="70"><template #default="{ row }"><span class="num">{{ row.models }}</span></template></el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? "启用" : "停用" }}</el-tag></template>
        </el-table-column>
        <el-table-column v-if="canEdit" label="操作" width="240" :fixed="narrow ? false : 'right'">
          <template #default="{ row }">
            <el-button link type="primary" :loading="testing === row.code" :disabled="!row.hasKey || !row.enabled" @click="test(row)">测试</el-button>
            <el-button link type="primary" :disabled="!row.hasKey" @click="openModelFor(row.code)">加模型</el-button>
            <el-button link type="primary" @click="openProvider(row)">编辑</el-button>
            <el-button link type="danger" @click="removeProvider(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 模型 -->
    <div class="card section">
      <div class="sec-head">
        <h2>模型</h2>
        <span class="muted small">勾了"App 可选"的模型会出现在对话输入框旁的切换里；单价是元 / 百万 Token，用来算费用</span>
        <el-button v-if="canEdit" type="primary" :icon="Plus" class="push" :disabled="!providers.length" @click="openModel()">添加模型</el-button>
      </div>
      <el-table v-loading="loading" :data="models" empty-text="还没有模型">
        <el-table-column label="模型" min-width="200">
          <template #default="{ row }">
            <div class="strong">{{ row.displayName }}</div>
            <div class="muted small mono">{{ row.providerCode }}:{{ row.model }}</div>
            <div v-if="row.description" class="muted small">{{ row.description }}</div>
          </template>
        </el-table-column>
        <el-table-column label="提供方" width="110"><template #default="{ row }">{{ providerName(row.providerCode) }}</template></el-table-column>
        <el-table-column label="能力" width="170">
          <template #default="{ row }">
            <div class="tags">
              <el-tag v-if="row.userSelectable" size="small" type="primary">App 可选</el-tag>
              <el-tag v-if="!row.supportsTools" size="small" type="warning">不支持工具</el-tag>
              <el-tag v-for="r in usedFor(row.id)" :key="r" size="small" type="success">{{ r }}</el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="单价 输入 / 缓存 / 输出" width="180">
          <template #default="{ row }">
            <span class="num small">{{ priceText(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? "启用" : "停用" }}</el-tag></template>
        </el-table-column>
        <el-table-column v-if="canEdit" label="操作" width="160" :fixed="narrow ? false : 'right'">
          <template #default="{ row }">
            <el-button link type="primary" :loading="testing === row.providerCode + ':' + row.model" @click="testModel(row)">测试</el-button>
            <el-button link type="primary" @click="openModel(row)">编辑</el-button>
            <el-button link type="danger" @click="removeModel(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 提供方表单 -->
    <el-dialog v-model="providerOpen" :title="editingCode ? `编辑「${pf.name}」` : '接入提供方'" width="min(560px, 94vw)">
      <el-form label-width="96px" @submit.prevent>
        <el-form-item v-if="!editingCode" label="常用">
          <div class="presets">
            <el-button v-for="p in PRESETS" :key="p.code" size="small" :type="preset === p.code ? 'primary' : 'default'" @click="applyPreset(p)">{{ p.name }}</el-button>
          </div>
        </el-form-item>
        <el-form-item label="名称"><el-input id="pf-name" v-model="pf.name" placeholder="例如 DeepSeek" /></el-form-item>
        <el-form-item label="标识">
          <el-input id="pf-code" v-model="pf.code" :disabled="!!editingCode" placeholder="小写字母，例如 deepseek" />
          <div class="field-hint">型号前缀，后台和日志里用"标识:型号"表示一个模型，创建后不能改</div>
        </el-form-item>
        <el-form-item label="接口类型">
          <el-radio-group v-model="pf.type">
            <el-radio-button value="openai">OpenAI 兼容</el-radio-button>
            <el-radio-button value="dashscope">百炼原生</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="pf.type === 'openai'" label="接口地址">
          <el-input id="pf-base" v-model="pf.baseUrl" placeholder="https://api.deepseek.com" />
          <div class="field-hint">不含 /v1/chat/completions</div>
        </el-form-item>
        <el-form-item v-if="pf.type === 'openai'" label="对话路径">
          <el-input id="pf-path" v-model="pf.completionsPath" placeholder="默认 /v1/chat/completions" />
        </el-form-item>
        <el-form-item label="API Key">
          <el-input id="pf-key" v-model="pf.apiKey" type="password" show-password autocomplete="new-password" :placeholder="editingKeyTail ? `已填（${editingKeyTail}），留空不改` : '粘贴 Key'" />
        </el-form-item>
        <el-form-item label="Key 请求头">
          <el-input id="pf-header" v-model="pf.authHeader" placeholder="留空：Authorization: Bearer" />
          <div class="field-hint">个别厂商要求把 Key 放在别的请求头里，例如小红书点点是 api-key</div>
        </el-form-item>
        <el-form-item label="关闭思考">
          <el-input id="pf-nothink" v-model="pf.noThinking" placeholder='可不填，例如 {"thinking":{"type":"disabled"}}' />
          <div class="field-hint">打分、分类这类小任务会带上这个参数，让模型不先想一遍，快很多</div>
        </el-form-item>
        <el-form-item label="启用"><el-switch v-model="pf.enabled" /></el-form-item>
        <div v-if="presetModel && !editingCode" class="field-hint indent">保存后会顺带添加模型 {{ presetModel.model }}{{ presetModel.supportsTools ? "" : "（不支持工具，不给 App 选）" }}</div>
      </el-form>
      <template #footer>
        <el-button @click="providerOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveProvider">保存</el-button>
      </template>
    </el-dialog>

    <!-- 模型表单 -->
    <el-dialog v-model="modelOpen" :title="editingModelId ? `编辑「${mf.displayName || mf.model}」` : '添加模型'" width="min(520px, 94vw)">
      <el-form label-width="96px" @submit.prevent>
        <el-form-item label="提供方">
          <el-select id="mf-provider" v-model="mf.providerCode" :disabled="!!editingModelId" class="full">
            <el-option v-for="p in providers" :key="p.code" :label="`${p.name}（${p.code}）`" :value="p.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="型号">
          <el-input v-if="editingModelId" id="mf-model" v-model="mf.model" disabled />
          <template v-else>
            <el-select
              id="mf-model"
              v-model="mf.model"
              filterable
              allow-create
              default-first-option
              :loading="remote.loading"
              placeholder="从列表里选，或直接输入型号"
              class="full"
              @change="pickRemote"
            >
              <el-option v-for="id in remote.ids" :key="id" :label="id" :value="id" :disabled="added(id)">
                <span>{{ id }}</span><span v-if="added(id)" class="muted small opt-tag">已添加</span>
              </el-option>
            </el-select>
            <div class="field-hint">
              {{ remote.loading ? "正在从提供方拉型号列表…" : remote.error || (remote.ids.length ? `从提供方拉到 ${remote.ids.length} 个型号；列表里没有的也可以直接输入` : "") }}
            </div>
          </template>
        </el-form-item>
        <el-form-item label="显示名"><el-input id="mf-name" v-model="mf.displayName" placeholder="App 里给用户看的名字，例如 DeepSeek" /></el-form-item>
        <el-form-item label="说明"><el-input id="mf-desc" v-model="mf.description" placeholder="可不填，例如 速度快、适合日常" /></el-form-item>
        <el-form-item label="支持工具">
          <el-switch v-model="mf.supportsTools" />
          <div class="field-hint">星络对话要查天气、行程、酒店等工具，不支持工具的模型不能用于对话</div>
        </el-form-item>
        <el-form-item label="App 可选"><el-switch v-model="mf.userSelectable" :disabled="!mf.supportsTools" /></el-form-item>
        <el-form-item label="单价">
          <div class="prices">
            <el-input-number id="mf-pin" v-model="mf.priceInput" :min="0" :precision="2" :controls="false" placeholder="输入" />
            <el-input-number id="mf-pcache" v-model="mf.priceCachedInput" :min="0" :precision="2" :controls="false" placeholder="缓存命中" />
            <el-input-number id="mf-pout" v-model="mf.priceOutput" :min="0" :precision="2" :controls="false" placeholder="输出" />
          </div>
          <div class="field-hint">元 / 百万 Token，依次是输入、缓存命中的输入、输出；不填就不算费用</div>
        </el-form-item>
        <el-form-item label="排序"><el-input-number id="mf-sort" v-model="mf.sort" :min="0" :max="999" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="mf.enabled" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="modelOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveModel">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { Plus } from "@element-plus/icons-vue";
import { api, type LlmModel, type LlmModelForm, type LlmProvider, type LlmProviderForm } from "../api";
import { isSuper } from "../session";
import { narrow } from "../utils";

/**
 * 模型配置：用途分配（对话、小任务、看图各用哪个模型）、提供方（地址和 Key）、模型（单价、App 里能不能选）。
 * 管理员都能看，只有超级管理员能改。
 */
const canEdit = computed(() => isSuper());
const providers = ref<LlmProvider[]>([]);
const models = ref<LlmModel[]>([]);
const roles = ref<Record<string, number | null>>({});
const loading = ref(false);
const saving = ref(false);
const testing = ref("");

const ROLE_INFO = [
  { role: "SKILL", label: "对话", hint: "回答用户、调工具查天气行程酒店；用户在 App 里自己选了模型时用用户选的" },
  { role: "ROUTER", label: "小任务", hint: "判断该用哪个技能、给酒店打分、生成追问建议；选快、便宜的" },
  { role: "VISION", label: "看图", hint: "识别上传的图片和扫描件（订单截图、行程单）" },
];

/** 一个常用提供方的默认填法；model 是接入后顺带添加的模型 */
interface Preset {
  code: string;
  name: string;
  type: "openai" | "dashscope";
  baseUrl?: string;
  completionsPath?: string;
  authHeader?: string;
  noThinking?: string;
  model?: { model: string; displayName: string; description?: string; supportsTools: boolean };
}

const PRESETS: Preset[] = [
  { code: "dashscope", name: "通义百炼", type: "dashscope" },
  { code: "deepseek", name: "DeepSeek", type: "openai", baseUrl: "https://api.deepseek.com", noThinking: '{"thinking":{"type":"disabled"}}' },
  { code: "moonshot", name: "Kimi", type: "openai", baseUrl: "https://api.moonshot.cn" },
  { code: "zhipu", name: "智谱", type: "openai", baseUrl: "https://open.bigmodel.cn/api/paas", completionsPath: "/v4/chat/completions" },
  { code: "doubao", name: "豆包", type: "openai", baseUrl: "https://ark.cn-beijing.volces.com/api", completionsPath: "/v3/chat/completions" },
  {
    code: "dots",
    name: "小红书点点",
    type: "openai",
    baseUrl: "https://note3-prev-api.askdiandian.com",
    completionsPath: "/v1/chat/completions",
    authHeader: "api-key",
    model: { model: "dots3-note-prev", displayName: "小红书点点", description: "小红书笔记风格，回复很慢（约 1 分钟）", supportsTools: false },
  },
  { code: "openai", name: "OpenAI", type: "openai", baseUrl: "https://api.openai.com" },
];

async function load() {
  loading.value = true;
  try {
    [providers.value, models.value, roles.value] = await Promise.all([api.llmProviders(), api.llmModels(), api.llmRoles()]);
  } finally {
    loading.value = false;
  }
}

function providerName(code: string): string {
  return providers.value.find((p) => p.code === code)?.name || code;
}

/** 某个用途能选的模型：启用的；对话只能选支持工具的 */
function roleOptions(role: string): LlmModel[] {
  return models.value.filter((m) => m.enabled && (role !== "SKILL" || m.supportsTools));
}

/** 这个模型正用于哪些用途 */
function usedFor(id: number): string[] {
  return ROLE_INFO.filter((r) => roles.value[r.role] === id).map((r) => r.label);
}

function priceText(m: LlmModel): string {
  if (m.priceInput == null && m.priceOutput == null) return "不计费";
  const f = (n: number | null) => (n == null ? "—" : String(Number(n)));
  return `${f(m.priceInput)} / ${f(m.priceCachedInput)} / ${f(m.priceOutput)}`;
}

async function assign(role: string, modelId: number) {
  await api.assignLlmRole(role, modelId);
  ElMessage.success("已更换，下一次对话开始生效");
  roles.value = await api.llmRoles();
}

// ---------- 测试连接 ----------

async function runTest(key: string, code: string, model?: string) {
  testing.value = key;
  try {
    const r = await api.testLlmProvider(code, model);
    const sec = (r.millis / 1000).toFixed(1);
    if (r.ok) ElMessageBox.alert(`型号 ${r.model} 用时 ${sec} 秒\n回复：${r.reply || "（空）"}`, "连接正常", { type: "success", customStyle: { whiteSpace: "pre-line" } }).catch(() => undefined);
    else ElMessageBox.alert(`型号 ${r.model} 用时 ${sec} 秒\n${r.error}`, "连接失败", { type: "error", customStyle: { whiteSpace: "pre-line" } }).catch(() => undefined);
  } finally {
    testing.value = "";
  }
}

function test(p: LlmProvider) {
  runTest(p.code, p.code);
}

function testModel(m: LlmModel) {
  runTest(`${m.providerCode}:${m.model}`, m.providerCode, m.model);
}

// ---------- 提供方 ----------

const providerOpen = ref(false);
const editingCode = ref("");
const editingKeyTail = ref("");
const preset = ref("");
const blankProvider = (): LlmProviderForm => ({ code: "", name: "", type: "openai", baseUrl: "", completionsPath: "", apiKey: "", authHeader: "", noThinking: "", enabled: true, sort: 0 });
const pf = reactive<LlmProviderForm>(blankProvider());
const presetModel = computed(() => PRESETS.find((p) => p.code === preset.value)?.model);

function openProvider(p?: LlmProvider) {
  Object.assign(pf, blankProvider());
  preset.value = "";
  editingCode.value = p?.code || "";
  editingKeyTail.value = p?.keyTail || "";
  if (p) {
    Object.assign(pf, {
      code: p.code, name: p.name, type: p.type, baseUrl: p.baseUrl || "", completionsPath: p.completionsPath || "",
      authHeader: p.authHeader || "", noThinking: p.noThinking || "", enabled: p.enabled, sort: p.sort,
    });
  }
  providerOpen.value = true;
}

function applyPreset(p: Preset) {
  preset.value = p.code;
  const taken = providers.value.some((x) => x.code === p.code);
  Object.assign(pf, {
    code: taken ? `${p.code}2` : p.code, name: p.name, type: p.type, baseUrl: p.baseUrl || "", completionsPath: p.completionsPath || "",
    authHeader: p.authHeader || "", noThinking: p.noThinking || "",
  });
}

async function saveProvider() {
  saving.value = true;
  try {
    const body = { ...pf, code: pf.code.trim(), name: pf.name.trim() };
    if (editingCode.value) {
      await api.updateLlmProvider(editingCode.value, body);
    } else {
      await api.createLlmProvider(body);
      const m = presetModel.value;
      if (m) {
        await api.createLlmModel({
          providerCode: body.code, model: m.model, displayName: m.displayName, description: m.description || null, enabled: true,
          userSelectable: false, supportsTools: m.supportsTools, priceInput: null, priceCachedInput: null, priceOutput: null, sort: 0,
        });
      }
    }
    providerOpen.value = false;
    ElMessage.success("已保存");
    load();
  } finally {
    saving.value = false;
  }
}

async function removeProvider(p: LlmProvider) {
  const ok = await ElMessageBox.confirm(`删除「${p.name}」和它的 Key？下面还有模型时要先删模型。`, "删除提供方", {
    type: "warning", confirmButtonText: "删除", cancelButtonText: "取消",
  }).then(() => true, () => false);
  if (!ok) return;
  await api.deleteLlmProvider(p.code);
  ElMessage.success("已删除");
  load();
}

// ---------- 模型 ----------

const modelOpen = ref(false);
const editingModelId = ref(0);
const blankModel = (): LlmModelForm => ({
  providerCode: "", model: "", displayName: "", description: "", enabled: true, userSelectable: false, supportsTools: true,
  priceInput: null, priceCachedInput: null, priceOutput: null, sort: 0,
});
const mf = reactive<LlmModelForm>(blankModel());

/** 添加模型时从提供方拉到的型号列表 */
const remote = reactive({ ids: [] as string[], loading: false, error: "" });

/** 选了提供方就去拉它的型号列表；拉不到就提示手填 */
async function loadRemote(code: string) {
  remote.ids = [];
  remote.error = "";
  if (!code) return;
  remote.loading = true;
  try {
    const ids = await api.llmRemoteModels(code);
    if (mf.providerCode === code) {
      remote.ids = ids;
      if (!ids.length) remote.error = "提供方返回的列表是空的，直接输入型号";
    }
  } catch (e) {
    if (mf.providerCode === code) remote.error = e instanceof Error ? e.message : "拉型号列表失败，直接输入型号";
  } finally {
    if (mf.providerCode === code) remote.loading = false;
  }
}

watch(
  () => [modelOpen.value, mf.providerCode] as const,
  ([open, code]) => {
    if (open && !editingModelId.value) loadRemote(code);
  },
);

/** 这个型号在当前提供方下已经加过了 */
function added(id: string): boolean {
  return models.value.some((m) => m.providerCode === mf.providerCode && m.model === id);
}

/** 选好型号后，显示名没填就先用型号名 */
function pickRemote(id: string) {
  if (!mf.displayName) mf.displayName = id;
}

/** 从提供方那一行直接加模型：提供方选好 */
function openModelFor(code: string) {
  openModel();
  mf.providerCode = code;
}

function openModel(m?: LlmModel) {
  Object.assign(mf, blankModel(), m ? { ...m, description: m.description || "" } : { providerCode: providers.value[0]?.code || "" });
  editingModelId.value = m?.id || 0;
  modelOpen.value = true;
}

async function saveModel() {
  saving.value = true;
  try {
    const body: LlmModelForm = {
      ...mf, model: mf.model.trim(), displayName: mf.displayName.trim(), description: (mf.description || "").trim() || null,
      userSelectable: mf.supportsTools && mf.userSelectable,
      // el-input-number 清空后是 undefined，统一成 null
      priceInput: mf.priceInput ?? null, priceCachedInput: mf.priceCachedInput ?? null, priceOutput: mf.priceOutput ?? null,
    };
    if (editingModelId.value) await api.updateLlmModel(editingModelId.value, body);
    else await api.createLlmModel(body);
    modelOpen.value = false;
    ElMessage.success("已保存");
    load();
  } finally {
    saving.value = false;
  }
}

async function removeModel(m: LlmModel) {
  const ok = await ElMessageBox.confirm(`删除模型「${m.displayName}」？选了它的用户会改回默认模型。`, "删除模型", {
    type: "warning", confirmButtonText: "删除", cancelButtonText: "取消",
  }).then(() => true, () => false);
  if (!ok) return;
  await api.deleteLlmModel(m.id);
  ElMessage.success("已删除");
  load();
}

onMounted(load);
</script>

<style scoped>
.section {
  margin-bottom: 16px;
}
.sec-head {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 4px 12px;
  margin-bottom: 12px;
}
.sec-head h2 {
  font-size: 16px;
  margin: 0;
}
.push {
  margin-left: auto;
}
.roles {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 12px;
}
.role {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px;
  border: 1px solid var(--x-line);
  border-radius: 10px;
}
.role-name {
  font-weight: 600;
}
.role-select,
.full {
  width: 100%;
}
.strong {
  font-weight: 600;
}
.small {
  font-size: 12px;
}
.code {
  margin-left: 6px;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  word-break: break-all;
}
.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.presets {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.presets .el-button + .el-button {
  margin-left: 0;
}
.prices {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.prices :deep(.el-input-number) {
  width: 110px;
}
.field-hint {
  font-size: 12px;
  color: var(--x-muted);
  line-height: 1.5;
  width: 100%;
}
.indent {
  padding-left: 96px;
}
.opt-tag { float: right; margin-left: 12px; }
</style>
