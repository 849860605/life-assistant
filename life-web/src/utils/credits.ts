import { computed, reactive } from "vue";
import { api } from "../api";
import type { CreditView } from "../api/types";

/** 我的积分，各处共用（账号菜单、对话页算"本次消耗"、积分页）；用完一次后调 refreshCredits 更新 */
export const credits = reactive<{ data: CreditView | null }>({ data: null });

/** 现在能用的积分：余额（透支时按 0 算）加今天送的 */
export const creditTotal = computed(() => {
  const v = credits.data?.view;
  return v ? Math.max(0, v.balance) + v.dailyLeft : null;
});

/** 重新取一次积分；返回能用的总数（取不到返回 null） */
export async function refreshCredits(): Promise<number | null> {
  try {
    credits.data = await api.credits();
    return creditTotal.value;
  } catch {
    return null;
  }
}

/** 航班动态能不能查、查一次多少积分、是不是会员；各卡片共用，第一次用到时向服务端要一次 */
export const flightInfo = reactive({ loaded: false, available: true, price: 0, member: false });
let loadingInfo: Promise<void> | null = null;

export function loadFlightInfo(): Promise<void> {
  loadingInfo ||= api.flightStatusInfo()
    .then((i) => void Object.assign(flightInfo, i, { loaded: true }))
    .catch(() => { loadingInfo = null; });
  return loadingInfo;
}
