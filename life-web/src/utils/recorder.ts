/**
 * 浏览器录音：用麦克风录一段，直接编码成 16kHz 单声道 16 位 WAV（iPhone Safari 和电脑 Chrome 出来的格式一样，
 * 后端不用转码）。浏览器只在 HTTPS 或 localhost 下才给麦克风权限。
 */
export const MAX_SECONDS = 60;
const TARGET_RATE = 16000;

export class RecorderError extends Error {}

export interface Recording {
  /** 停止录音，返回 WAV；基本没声音（只有底噪）时返回 null */
  stop(): Promise<Blob | null>;
  /** 放弃这次录音 */
  cancel(): void;
}

/** 当前环境能不能录音（不能的话给出原因） */
export function recordUnsupported(): string {
  // #ifdef H5
  if (!window.isSecureContext) return "浏览器只在 HTTPS 网址下才允许用麦克风，网站开通 HTTPS 后就能语音输入";
  if (!navigator.mediaDevices?.getUserMedia) return "这个浏览器不支持录音";
  return "";
  // #endif
  // #ifndef H5
  return "暂时只支持在浏览器里语音输入";
  // #endif
}

/** 开始录音；onTick 每秒回调一次已录秒数，到最长时间自动停（onLimit）。 */
export async function startRecording(onTick: (s: number) => void, onLimit: () => void): Promise<Recording> {
  const reason = recordUnsupported();
  if (reason) throw new RecorderError(reason);
  let stream: MediaStream;
  try {
    stream = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true } });
  } catch (e: any) {
    throw new RecorderError(e?.name === "NotAllowedError" ? "没有麦克风权限，在浏览器设置里允许后再试" : "打不开麦克风：" + (e?.message || e));
  }
  const Ctx = window.AudioContext || (window as any).webkitAudioContext;
  const ctx: AudioContext = new Ctx();
  const source = ctx.createMediaStreamSource(stream);
  const node = ctx.createScriptProcessor(4096, 1, 1);
  const chunks: Float32Array[] = [];
  node.onaudioprocess = (ev) => chunks.push(new Float32Array(ev.inputBuffer.getChannelData(0)));
  source.connect(node);
  node.connect(ctx.destination);
  const started = Date.now();
  const timer = setInterval(() => {
    const s = Math.floor((Date.now() - started) / 1000);
    onTick(s);
    if (s >= MAX_SECONDS) onLimit();
  }, 1000);

  const close = () => {
    clearInterval(timer);
    node.disconnect();
    source.disconnect();
    stream.getTracks().forEach((t) => t.stop());
    ctx.close().catch(() => {});
  };
  return {
    async stop() {
      close();
      return loudEnough(chunks) ? encodeWav(chunks, ctx.sampleRate) : null;
    },
    cancel: close,
  };
}

/** 有没有说话：最响的一段（约 0.1 秒）音量够不够，只有底噪的不上传 */
function loudEnough(chunks: Float32Array[]): boolean {
  let peak = 0;
  for (const c of chunks) {
    let sum = 0;
    for (let i = 0; i < c.length; i++) sum += c[i] * c[i];
    peak = Math.max(peak, Math.sqrt(sum / c.length));
  }
  return peak > 0.01;
}

/** 拼起来、降采样到 16kHz、写成 WAV */
function encodeWav(chunks: Float32Array[], rate: number): Blob {
  const total = chunks.reduce((n, c) => n + c.length, 0);
  const all = new Float32Array(total);
  let off = 0;
  for (const c of chunks) {
    all.set(c, off);
    off += c.length;
  }
  const ratio = rate / TARGET_RATE;
  const len = Math.floor(total / ratio);
  const pcm = new Int16Array(len);
  for (let i = 0; i < len; i++) {
    // 简单平均降采样，语音识别够用
    const start = Math.floor(i * ratio);
    const end = Math.min(Math.floor((i + 1) * ratio), total);
    let sum = 0;
    for (let j = start; j < end; j++) sum += all[j];
    const v = Math.max(-1, Math.min(1, end > start ? sum / (end - start) : 0));
    pcm[i] = v < 0 ? v * 0x8000 : v * 0x7fff;
  }
  const buf = new ArrayBuffer(44 + pcm.length * 2);
  const view = new DataView(buf);
  const str = (o: number, s: string) => [...s].forEach((ch, i) => view.setUint8(o + i, ch.charCodeAt(0)));
  str(0, "RIFF");
  view.setUint32(4, 36 + pcm.length * 2, true);
  str(8, "WAVE");
  str(12, "fmt ");
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true);
  view.setUint16(22, 1, true);
  view.setUint32(24, TARGET_RATE, true);
  view.setUint32(28, TARGET_RATE * 2, true);
  view.setUint16(32, 2, true);
  view.setUint16(34, 16, true);
  str(36, "data");
  view.setUint32(40, pcm.length * 2, true);
  new Int16Array(buf, 44).set(pcm);
  return new Blob([buf], { type: "audio/wav" });
}
