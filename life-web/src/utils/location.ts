import { ref } from "vue";
import { api } from "../api";

const KEY = "life_location";
/** 位置在这段时间内重复使用，不每次都弹定位 */
const FRESH_MS = 10 * 60 * 1000;

/** 当前位置：高德坐标、所在城市、区县、地址，at 是定位时间。 */
export interface MyLocation {
  lng: number;
  lat: number;
  city: string;
  district: string | null;
  address: string | null;
  at: number;
}

/** 最近一次定位结果（10 分钟内有效），只存在本机，不上传保存。 */
export const myLocation = ref<MyLocation | null>(load());

function load(): MyLocation | null {
  try {
    const saved = JSON.parse(uni.getStorageSync(KEY) || "null") as MyLocation | null;
    return saved && Date.now() - saved.at < FRESH_MS ? saved : null;
  } catch {
    return null;
  }
}

/** 浏览器给的原始 GPS 坐标（WGS-84）。 */
function gps(): Promise<{ lng: number; lat: number }> {
  return new Promise((resolve, reject) => {
    // #ifdef H5
    if (!window.isSecureContext) {
      reject(new Error("定位需要 HTTPS 访问，现在的地址不支持"));
      return;
    }
    if (!navigator.geolocation) {
      reject(new Error("这个浏览器不支持定位"));
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (p) => resolve({ lng: p.coords.longitude, lat: p.coords.latitude }),
      (e) => reject(new Error(e.code === e.PERMISSION_DENIED
        ? "没有定位权限：请在浏览器设置里允许本网站使用位置"
        : e.code === e.TIMEOUT ? "定位超时，到开阔的地方再试" : "暂时拿不到位置")),
      { enableHighAccuracy: true, timeout: 10000, maximumAge: 60000 },
    );
    // #endif
    // #ifndef H5
    uni.getLocation({
      type: "wgs84",
      success: (r) => resolve({ lng: r.longitude, lat: r.latitude }),
      fail: () => reject(new Error("没有定位权限或暂时拿不到位置")),
    });
    // #endif
  });
}

/**
 * 定位：拿到 GPS 坐标后让后端换成高德坐标并查出城市。10 分钟内的结果直接复用，force 时重新定位。
 * 第一次会弹出浏览器的授权提示；拒绝或失败时抛出带说明的错误。
 */
export async function locate(force = false): Promise<MyLocation> {
  const cached = load();
  if (cached && !force) {
    myLocation.value = cached;
    return cached;
  }
  const p = await gps();
  const r = await api.resolveLocation(p.lng, p.lat);
  const loc: MyLocation = { ...r, at: Date.now() };
  uni.setStorageSync(KEY, JSON.stringify(loc));
  myLocation.value = loc;
  return loc;
}

/** 用户以前是否已经允许过定位（允许过就可以直接定位，不会弹窗打扰）。 */
export async function locationAllowed(): Promise<boolean> {
  // #ifdef H5
  try {
    const status = await navigator.permissions?.query({ name: "geolocation" as PermissionName });
    return status?.state === "granted";
  } catch {
    return false;
  }
  // #endif
  // #ifndef H5
  return false;
  // #endif
}
