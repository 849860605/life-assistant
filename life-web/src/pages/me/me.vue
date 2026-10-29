<template>
  <view class="container">
    <view v-if="user" class="card account">
      <view>
        <view class="name">{{ user.nickname || user.username }}</view>
        <view class="muted small">{{ user.username }} · {{ user.timezone }}</view>
      </view>
      <button class="btn ghost small" @click="logout">退出登录</button>
    </view>

    <!-- 记忆 -->
    <view class="section-title">助手记住的事</view>
    <view class="card">
      <view v-if="!memories.length" class="muted small">还没有记忆。对话里说"记住：我不吃辣"，或者在下面添加。</view>
      <view v-for="m in memories" :key="m.id" class="memory">
        <text class="chip">{{ CATEGORY[m.category] }}</text>
        <view class="memory-body">
          <view>{{ m.content }}</view>
          <view class="muted small">
            {{ m.source === "USER_STATED" ? "你说的" : m.source === "AI_INFERRED" ? "助手推断" : "手动添加" }}
            <text v-if="m.sensitive"> · 加密保存，不发给模型</text>
          </view>
        </view>
        <text class="del" @click="removeMemory(m)">删除</text>
      </view>
      <view class="add-memory">
        <picker :range="categoryLabels" :value="memoryForm.categoryIndex"
                @change="memoryForm.categoryIndex = Number($event.detail.value)">
          <view class="input picker">{{ categoryLabels[memoryForm.categoryIndex] }}</view>
        </picker>
        <input id="memory-content" v-model="memoryForm.content" class="input grow" placeholder="比如：海鲜过敏" />
        <button class="btn small" :disabled="!memoryForm.content.trim()" @click="addMemory">添加</button>
      </view>
    </view>

    <!-- Bark 推送 -->
    <view class="section-title">iPhone 推送（Bark）</view>
    <view class="card stack">
      <view class="muted small">在 iPhone 上装 Bark，把 App 首页链接里的那段 Key 填到这里，日程提醒就会推到手机上。</view>
      <view class="row">
        <input id="bark-key" v-model="barkKey" class="input grow" placeholder="Bark 设备 Key" />
        <button class="btn small" :disabled="!barkKey.trim()" @click="saveBark">保存</button>
      </view>
      <button class="btn ghost small self-start" @click="testPush">发一条测试推送</button>
    </view>

    <!-- 日历订阅 -->
    <view class="section-title">日历订阅</view>
    <view class="card stack">
      <view class="muted small">
        iPhone：设置 → 日历 → 账户 → 添加账户 → 其他 → 添加已订阅的日历，填下面的地址。地址要用部署后的公网域名。
      </view>
      <view v-if="feedUrl" class="feed">
        <text class="feed-url">{{ feedUrl }}</text>
        <button class="btn small" @click="copy(feedUrl)">复制</button>
        <view class="muted small">这个地址只显示这一次，关掉页面就看不到了。</view>
      </view>
      <button class="btn ghost small self-start" @click="createFeed">生成订阅地址</button>
      <view v-for="t in tokens" :key="t.id" class="token">
        <text>{{ t.name }}</text>
        <text class="muted small mono">{{ t.prefix }}…</text>
        <text class="del" @click="revoke(t)">吊销</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { reactive, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { api } from "../../api";
import { showError, toLogin } from "../../api/http";
import type { MemoryCategory, MemoryItem, TokenView, UserView } from "../../api/types";

const CATEGORY: Record<MemoryCategory, string> = {
  PROFILE: "基本信息",
  PREFERENCE: "偏好",
  RELATION: "家人朋友",
  HEALTH: "健康",
  FINANCE: "财务",
};
const categories = Object.keys(CATEGORY) as MemoryCategory[];
const categoryLabels = categories.map((c) => CATEGORY[c]);

const user = ref<UserView | null>(null);
const memories = ref<MemoryItem[]>([]);
const tokens = ref<TokenView[]>([]);
const barkKey = ref("");
const feedUrl = ref("");
const memoryForm = reactive({ categoryIndex: 1, content: "" });

onShow(async () => {
  try {
    const [me, mems, toks] = await Promise.all([api.me(), api.memories(), api.tokens()]);
    user.value = me;
    memories.value = mems;
    tokens.value = toks.filter((t) => t.scope === "CALENDAR_FEED");
  } catch (e) {
    showError(e);
  }
});

/** 手动添加一条记忆；键用时间戳生成，同类同键才会覆盖，所以不会冲掉别的记忆。 */
async function addMemory() {
  try {
    await api.saveMemory({
      category: categories[memoryForm.categoryIndex],
      key: `manual_${Date.now()}`,
      content: memoryForm.content.trim(),
    });
    memoryForm.content = "";
    memories.value = await api.memories();
  } catch (e) {
    showError(e);
  }
}

function removeMemory(m: MemoryItem) {
  confirm("删除记忆", `删除"${m.content}"？`, async () => {
    await api.deleteMemory(m.id);
    memories.value = await api.memories();
  });
}

async function saveBark() {
  try {
    await api.configureBark(barkKey.value.trim());
    uni.showToast({ title: "已保存", icon: "success" });
  } catch (e) {
    showError(e);
  }
}

async function testPush() {
  try {
    await api.testNotify();
    uni.showToast({ title: "已发送，看看手机", icon: "none" });
  } catch (e) {
    showError(e);
  }
}

/** 生成日历订阅用的个人令牌，地址只在这次显示。 */
async function createFeed() {
  try {
    const token = await api.createToken("iPhone 日历", "CALENDAR_FEED");
    feedUrl.value = `${location.origin}/api/v1/calendar/feed/${token.plainKey}.ics`;
    tokens.value = (await api.tokens()).filter((t) => t.scope === "CALENDAR_FEED");
  } catch (e) {
    showError(e);
  }
}

/** 吊销日历订阅地址。 */
function revoke(t: TokenView) {
  confirm("吊销订阅地址", "吊销后用这个地址订阅的日历会停止同步。", async () => {
    await api.revokeToken(t.id);
    tokens.value = tokens.value.filter((x) => x.id !== t.id);
  });
}

function copy(text: string) {
  uni.setClipboardData({ data: text });
}

async function logout() {
  try {
    await api.logout();
  } finally {
    toLogin();
  }
}

/** 二次确认后执行，出错弹提示。 */
function confirm(title: string, content: string, action: () => Promise<void>) {
  uni.showModal({
    title,
    content,
    confirmText: "确定",
    confirmColor: "#B4442F",
    success: async (res) => {
      if (!res.confirm) return;
      try {
        await action();
      } catch (e) {
        showError(e);
      }
    },
  });
}
</script>

<style scoped>
.account { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.account .btn { margin: 0; }
.name { font-size: 17px; font-weight: 600; }
.small { font-size: 13px; }
.mono { font-family: var(--mono); }
.memory {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 0;
  border-bottom: 1px solid var(--line);
}
.memory .chip { margin-top: 2px; }
.memory-body { flex: 1; min-width: 0; word-break: break-word; }
.add-memory { display: flex; flex-wrap: wrap; gap: 8px; padding-top: 12px; align-items: center; }
.add-memory .btn { margin: 0; }
.picker { display: flex; align-items: center; min-width: 96px; }
.grow { flex: 1; min-width: 140px; }
.stack { display: flex; flex-direction: column; gap: 10px; }
.stack .btn { margin: 0; }
.self-start { align-self: flex-start; }
.row { display: flex; gap: 8px; align-items: center; }
.row .btn { margin: 0; }
.feed { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; background: var(--surface-2); padding: 10px; border-radius: 8px; }
.feed .btn { margin: 0; }
.feed-url { flex: 1; min-width: 0; font-family: var(--mono); font-size: 12px; word-break: break-all; }
.token { display: flex; align-items: center; gap: 10px; font-size: 14px; }
.del { margin-left: auto; font-size: 13px; color: var(--muted); cursor: pointer; flex-shrink: 0; }
.del:hover { color: var(--danger); }
</style>
