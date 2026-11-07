<template>
  <view class="card addr">
    <view class="muted small">出发默认地址用来算首页"今日安排"几点出发、路上多久（旅行中按当晚住的酒店算），星络对话时也会知道。</view>
    <view v-if="!loaded" class="muted small">加载中…</view>
    <view v-for="a in list" :key="a.id" class="row">
      <view class="row-main">
        <view class="row-title">{{ a.label }}<text v-if="a.isDefault" class="tag">出发默认</text></view>
        <view class="muted small">{{ a.address }}</view>
      </view>
      <text v-if="!a.isDefault" class="link" @click="makeDefault(a)">设为默认</text>
      <text class="link" @click="openForm(a)">编辑</text>
      <text class="link danger" @click="remove(a)">删除</text>
    </view>
    <view v-if="loaded && !list.length" class="muted small">还没有常用地址。</view>

    <view v-if="form" class="form">
      <view class="form-row">
        <input id="addr-label" v-model="form.label" class="input label-in" maxlength="20" placeholder="名称：家、公司" />
        <input id="addr-text" v-model="form.address" class="input grow" maxlength="120" placeholder="地址，带上城市和小区名，比如：深圳市南山区科技园某某小区" />
      </view>
      <label class="check"><checkbox :checked="form.isDefault" @click="form.isDefault = !form.isDefault" /><text>设为出发默认地址</text></label>
      <view v-if="error" class="err">{{ error }}</view>
      <view class="form-acts">
        <button class="btn ghost small" @click="form = null">取消</button>
        <button class="btn small" :disabled="saving" @click="save">{{ saving ? "查找地址中…" : "保存" }}</button>
      </view>
    </view>
    <button v-else class="btn ghost small add" @click="openForm()">添加地址</button>
  </view>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { api } from "../api";
import { showError } from "../api/http";
import type { UserAddress } from "../api/types";

/** 「我的」里的常用地址：新增、修改、删除（要确认）、设出发默认地址。地址要在高德上找得到才存。 */
const list = ref<UserAddress[]>([]);
const loaded = ref(false);
const saving = ref(false);
const error = ref("");
const form = ref<{ id?: number; label: string; address: string; isDefault: boolean } | null>(null);

onMounted(async () => {
  try {
    list.value = await api.addresses();
  } catch (e) {
    showError(e);
  } finally {
    loaded.value = true;
  }
});

function openForm(a?: UserAddress) {
  error.value = "";
  form.value = a ? { id: a.id, label: a.label, address: a.address, isDefault: a.isDefault } : { label: list.value.length ? "" : "家", address: "", isDefault: !list.value.length };
}

async function save() {
  const f = form.value;
  if (!f) return;
  if (!f.label.trim()) return void (error.value = "请填写名称，比如：家、公司");
  if (!f.address.trim()) return void (error.value = "请填写地址");
  error.value = "";
  saving.value = true;
  try {
    const body = { label: f.label.trim(), address: f.address.trim(), isDefault: f.isDefault };
    list.value = f.id ? await api.updateAddress(f.id, body) : await api.createAddress(body);
    form.value = null;
    uni.showToast({ title: "已保存", icon: "none" });
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    saving.value = false;
  }
}

async function makeDefault(a: UserAddress) {
  try {
    list.value = await api.defaultAddress(a.id);
    uni.showToast({ title: `出发默认地址改成"${a.label}"`, icon: "none" });
  } catch (e) {
    showError(e);
  }
}

function remove(a: UserAddress) {
  uni.showModal({
    title: "删除地址",
    content: `删除"${a.label}"？${a.isDefault ? "它是出发默认地址，删掉后会把下一个地址设成默认。" : ""}`,
    confirmText: "删除",
    confirmColor: "#C4372B",
    success: async (r) => {
      if (!r.confirm) return;
      try {
        list.value = await api.deleteAddress(a.id);
      } catch (e) {
        showError(e);
      }
    },
  });
}
</script>

<style scoped>
.addr { display: flex; flex-direction: column; gap: 10px; }
.row { display: flex; align-items: center; gap: 14px; padding-top: 10px; border-top: 1px solid var(--line); font-size: 14px; }
.row-main { flex: 1; min-width: 0; }
.row-title { font-weight: 600; }
.tag { margin-left: 8px; font-size: 11px; font-weight: 400; padding: 1px 6px; border-radius: 4px; background: var(--accent-soft); color: var(--accent); }
.link { color: var(--accent); cursor: pointer; font-size: 13px; white-space: nowrap; }
.link.danger { color: var(--danger); }
.form { display: flex; flex-direction: column; gap: 10px; padding-top: 10px; border-top: 1px solid var(--line); }
.form-row { display: flex; gap: 8px; flex-wrap: wrap; }
.label-in { width: 120px; }
.grow { flex: 1; min-width: 200px; }
.check { display: flex; align-items: center; gap: 6px; font-size: 14px; }
.err { font-size: 13px; color: var(--danger); }
.form-acts { display: flex; justify-content: flex-end; gap: 8px; }
.form-acts .btn, .add { margin: 0; }
.add { align-self: flex-start; }
.muted { color: var(--muted); }
.small { font-size: 12px; }
@media (max-width: 767px) { .row { flex-wrap: wrap; gap: 6px 14px; } .row-main { flex-basis: 100%; } }
</style>
