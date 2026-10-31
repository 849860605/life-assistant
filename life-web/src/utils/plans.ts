const PLANS_KEY = "life_last_plans";

/** 最近一次的三套方案（方案卡的 data）和它所在的会话，方案对比页从这里读。 */
export interface SavedPlans {
  data: any;
  conversationId: string | null;
}

export function savePlans(data: any, conversationId: string | null) {
  uni.setStorageSync(PLANS_KEY, JSON.stringify({ data, conversationId }));
}

export function loadPlans(): SavedPlans | null {
  try {
    const raw = uni.getStorageSync(PLANS_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}
