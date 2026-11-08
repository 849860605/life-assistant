import { reactive } from "vue";
import { api } from "../api";

/** 航班动态的免费试查次数，各卡片共用：第一次用到时向服务端要一次，查过动态后用返回的次数更新 */
export const flightTrial = reactive({ loaded: false, available: true, member: false, total: 0, left: 0 });

let loading: Promise<void> | null = null;

/** 拿一次试查次数；失败了下次再试 */
export function loadFlightTrial(): Promise<void> {
  loading ||= api.flightTrial()
    .then((t) => void Object.assign(flightTrial, t, { loaded: true }))
    .catch(() => { loading = null; });
  return loading;
}
