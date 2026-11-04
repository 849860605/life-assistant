<template>
  <view v-if="canSwitchModel" class="mp" @click.stop>
    <view class="mp-chip" :class="{ small }" @click="toggle">
      <Icon name="sparkle" :size="13" color="var(--accent)" />
      <text class="mp-name">{{ currentModelName }}</text>
      <Icon name="down" :size="11" color="var(--muted)" />
    </view>
    <template v-if="open">
      <view class="mp-mask" @click="open = false" />
      <view class="mp-menu">
        <view class="mp-title">星络用哪个模型回答</view>
        <view v-for="m in modelChoices?.models" :key="m.id" class="mp-item" :class="{ on: m.id === currentModelId }" @click="pick(m.id)">
          <view class="mp-text">
            <text class="mp-item-name">{{ m.name }}<text v-if="m.id === modelChoices?.defaultId" class="mp-def"> 默认</text></text>
            <text v-if="m.description" class="mp-desc">{{ m.description }}</text>
          </view>
          <Icon v-if="m.id === currentModelId" name="check" :size="14" color="var(--accent)" />
        </view>
      </view>
    </template>
  </view>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import Icon from "./Icon.vue";
import { canSwitchModel, chooseModel, currentModelId, currentModelName, loadModels, modelChoices } from "../utils/models";

/**
 * 输入框旁的模型切换：电脑上点开一个小菜单，手机上弹系统的选项列表。
 * 后台只开放了一个模型（或没开放）时不显示。选择存在账号上，之后的对话都用它。
 */
const props = defineProps<{ wide?: boolean; small?: boolean }>();
const open = ref(false);

onMounted(loadModels);

function toggle() {
  const list = modelChoices.value?.models || [];
  if (props.wide) {
    open.value = !open.value;
    return;
  }
  uni.showActionSheet({
    itemList: list.map((m) => (m.id === currentModelId.value ? `✓ ${m.name}` : m.name)),
    success: (r) => pick(list[r.tapIndex].id),
  });
}

async function pick(id: number) {
  open.value = false;
  if (id === currentModelId.value) return;
  try {
    await chooseModel(id);
    uni.showToast({ title: `已换成 ${currentModelName.value}`, icon: "none" });
  } catch {
    uni.showToast({ title: "没换成，稍后再试", icon: "none" });
  }
}
</script>

<style scoped>
.mp { position: relative; }
.mp-chip { display: flex; align-items: center; gap: 4px; font-size: 12px; padding: 4px 8px; border-radius: 8px; background: var(--surface-2); cursor: pointer; max-width: 160px; }
.mp-chip.small { padding: 4px 6px; }
.mp-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mp-mask { position: fixed; inset: 0; z-index: 40; }
.mp-menu { position: absolute; right: 0; bottom: calc(100% + 6px); z-index: 41; width: 240px; background: var(--surface); border: 1px solid var(--line); border-radius: 12px; box-shadow: 0 8px 24px rgba(0, 0, 0, 0.12); padding: 6px; }
.mp-title { font-size: 12px; color: var(--muted); padding: 6px 8px; }
.mp-item { display: flex; align-items: center; gap: 8px; padding: 8px; border-radius: 8px; cursor: pointer; }
.mp-item:hover { background: var(--surface-2); }
.mp-item.on .mp-item-name { color: var(--accent); font-weight: 600; }
.mp-text { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.mp-item-name { font-size: 13px; }
.mp-def { font-size: 11px; color: var(--muted); font-weight: 400; }
.mp-desc { font-size: 11px; color: var(--muted); }
</style>
