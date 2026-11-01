import { api } from "../api";
import { ensureMediaTicket } from "./media";

/**
 * 高德 JS API 2.0：后端配了 JS Key 才加载（只在 H5）；安全密钥不在前端，serviceHost 指向后端转发接口，
 * 路径里带下载票据。加载不了返回 null，地图组件就用静态图片。
 */
let loading: Promise<any | null> | null = null;

export function loadAmap(): Promise<any | null> {
  // #ifndef H5
  return Promise.resolve(null);
  // #endif
  // #ifdef H5
  const pending = loading || load().catch(() => null);
  loading = pending;
  return pending;
  // #endif
}

async function load(): Promise<any | null> {
  const w = window as any;
  if (w.AMap) return w.AMap;
  const { key } = await api.mapJsConfig();
  if (!key) return null;
  const ticket = await ensureMediaTicket();
  if (!ticket) return null;
  w._AMapSecurityConfig = { serviceHost: `${location.origin}/api/v1/maps/amap/${ticket}` };
  await new Promise<void>((resolve, reject) => {
    const s = document.createElement("script");
    s.src = `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(key)}`;
    s.async = true;
    s.onload = () => resolve();
    s.onerror = () => reject(new Error("高德地图脚本加载失败"));
    document.head.appendChild(s);
  });
  return w.AMap || null;
}
