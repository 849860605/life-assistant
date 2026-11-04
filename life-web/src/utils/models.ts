import { computed, ref } from "vue";
import { api } from "../api";
import type { ModelChoices } from "../api/types";

/** 对话模型的选择：首页和对话页共用一份，一处改了另一处跟着变；选择存在账号上。 */
const choices = ref<ModelChoices | null>(null);
let loading: Promise<void> | null = null;

/** 取一次可选模型（已经取过就不再取）；失败时当作没有可选的，不显示切换。 */
export function loadModels(): Promise<void> {
  if (choices.value) return Promise.resolve();
  if (!loading) {
    loading = api
      .models()
      .then((c) => {
        choices.value = c;
      })
      .catch(() => undefined)
      .finally(() => {
        loading = null;
      });
  }
  return loading;
}

export const modelChoices = computed(() => choices.value);

/** 当前用的模型 id：自己选的，没选就是默认的。 */
export const currentModelId = computed(() => choices.value?.selected ?? choices.value?.defaultId ?? null);

/** 当前模型的名字，没有可选模型时为空。 */
export const currentModelName = computed(() => choices.value?.models.find((m) => m.id === currentModelId.value)?.name || "");

/** 只有一个或没有可选模型时不显示切换。 */
export const canSwitchModel = computed(() => (choices.value?.models.length || 0) > 1);

/** 换成某个模型；选的正好是默认的就存成"用默认"，以后后台换了默认也跟着变。 */
export async function chooseModel(id: number) {
  const c = choices.value;
  const value = c && id === c.defaultId ? null : id;
  choices.value = await api.chooseModel(value);
}
