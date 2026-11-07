import { api } from "../api";
import { showError } from "../api/http";
import type { Conversation } from "../api/types";
import { splitAttachments } from "./attach";
import { markdownToPlain } from "./format";

/** 一段对话按"我 / 星络"排成文字；markdown 为 true 时保留回答里的 Markdown（导出用），否则转成纯文本（复制分享用） */
async function transcript(c: Conversation, markdown: boolean): Promise<string> {
  const list = (await api.messages(c.id)).filter((m) => m.role === "USER" || m.role === "ASSISTANT").sort((a, b) => a.id - b.id);
  const title = c.title || "和星络的对话";
  const lines = [markdown ? `# ${title}` : title, ""];
  for (const m of list) {
    if (m.role === "USER") {
      const { text, files } = splitAttachments(m.content);
      const att = files.length ? `（附件：${files.map((f) => f.name).join("、")}）` : "";
      lines.push(markdown ? `**我**：${text}${att}` : `我：${text}${att}`, "");
    } else if (m.content.trim()) {
      lines.push(markdown ? `**星络**：\n\n${m.content}` : `星络：${markdownToPlain(m.content)}`, "");
    }
  }
  return lines.join("\n");
}

/** 分享：把整段对话复制成文字，可以粘贴发给别人（没有公开链接，别人看不到你的账号） */
export async function shareConversation(c: Conversation) {
  try {
    const text = await transcript(c, false);
    uni.setClipboardData({
      data: text,
      success: () => uni.showToast({ title: "对话已复制，可以粘贴发给别人", icon: "none" }),
      fail: () => uni.showToast({ title: "复制失败", icon: "none" }),
    });
  } catch (e) {
    showError(e);
  }
}

/** 导出：下载成一个 Markdown 文件 */
export async function exportConversation(c: Conversation) {
  try {
    const text = await transcript(c, true);
    // #ifdef H5
    const blob = new Blob([text], { type: "text/markdown;charset=utf-8" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = `${(c.title || "星络对话").replace(/[\\/:*?"<>|]/g, " ").slice(0, 40)}.md`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
    // #endif
  } catch (e) {
    showError(e);
  }
}

/** 置顶或取消置顶，返回更新后的会话；失败返回空 */
export async function togglePin(c: Conversation): Promise<Conversation | null> {
  try {
    const updated = await api.pinConversation(c.id, !c.pinnedAt);
    uni.showToast({ title: updated.pinnedAt ? "已置顶" : "已取消置顶", icon: "none" });
    return updated;
  } catch (e) {
    showError(e);
    return null;
  }
}

/** 删除前确认；删掉了返回 true */
export function deleteConversation(c: Conversation): Promise<boolean> {
  return new Promise((resolve) => {
    uni.showModal({
      title: "删除此对话",
      content: `删除"${c.title || "新对话"}"？聊天记录会一起删掉，不能恢复。`,
      confirmText: "删除",
      confirmColor: "#C4372B",
      success: async (res) => {
        if (!res.confirm) return resolve(false);
        try {
          await api.deleteConversation(c.id);
          resolve(true);
        } catch (e) {
          showError(e);
          resolve(false);
        }
      },
      fail: () => resolve(false),
    });
  });
}
