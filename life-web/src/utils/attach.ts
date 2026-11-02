import { API_BASE } from "../api/http";
import { mediaTicket } from "./media";

/** 消息里带的一个附件：发消息时后端把识别出的文字拼成〔附件：文件名｜id〕…〔附件结束〕 */
export interface Attached {
  id: string;
  name: string;
}

const BLOCK = /\n*〔附件：([^｜〕\n]+)｜([0-9a-f-]{36})〕[\s\S]*?〔附件结束〕/g;

/** 把用户消息拆成自己打的字和附件列表，识别出的长文字不在气泡里显示。 */
export function splitAttachments(text: string): { text: string; files: Attached[] } {
  const files: Attached[] = [];
  const rest = text.replace(BLOCK, (_, name: string, id: string) => {
    files.push({ id, name });
    return "";
  });
  return { text: rest.trim(), files };
}

/** 支持上传的扩展名，和后端 UploadKind 一致 */
export const UPLOAD_EXTS = [".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif", ".pdf", ".docx", ".xlsx", ".xls", ".txt", ".md", ".csv"];

/** 原文件地址（图片、PDF 在新标签页打开，其他下载），用下载票据证明身份。 */
export function fileUrl(id: string): string {
  return `${API_BASE}/api/v1/files/${id}/raw?mt=${encodeURIComponent(mediaTicket.value)}`;
}

/** 按扩展名选图标 */
export function fileIcon(name: string): string {
  return /\.(jpe?g|png|webp|bmp|gif)$/i.test(name) ? "layers" : /\.(xlsx?|csv)$/i.test(name) ? "wallet" : "bookmark";
}
