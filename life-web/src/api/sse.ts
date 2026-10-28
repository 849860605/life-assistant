import { API_BASE, ApiError, getToken, toLogin } from "./http";
import type { ChatEvent } from "./types";

/**
 * 发一条消息并按 SSE 逐个回调事件。用 fetch 读流，只在 H5 可用；
 * 以后出小程序时改用 uni.request 的 enableChunked。signal 用来中途停止。
 */
export async function streamMessage(conversationId: string, content: string,
                                    onEvent: (event: ChatEvent) => void, signal?: AbortSignal): Promise<void> {
  const res = await fetch(`${API_BASE}/api/v1/conversations/${conversationId}/messages`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${getToken()}`,
      "Content-Type": "application/json",
      Accept: "text/event-stream",
    },
    body: JSON.stringify({ content }),
    signal,
  });
  if (res.status === 401) {
    toLogin();
    throw new ApiError(401, "请先登录");
  }
  if (!res.ok || !res.body) {
    let message = `发送失败（${res.status}）`;
    try {
      message = (await res.json()).message || message;
    } catch {
      // 不是 JSON，用默认提示
    }
    throw new ApiError(res.status, message);
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  for (;;) {
    const { value, done } = await reader.read();
    if (done) {
      break;
    }
    buffer += decoder.decode(value, { stream: true });
    // 事件之间用空行分隔
    let boundary = buffer.search(/\r?\n\r?\n/);
    while (boundary >= 0) {
      const block = buffer.slice(0, boundary);
      buffer = buffer.slice(boundary).replace(/^\r?\n\r?\n/, "");
      const event = parseBlock(block);
      if (event) {
        onEvent(event);
      }
      boundary = buffer.search(/\r?\n\r?\n/);
    }
  }
}

/** 把一个 SSE 块里的 data 行拼起来解析成事件；没有 data 的块（注释、心跳）返回 null。 */
function parseBlock(block: string): ChatEvent | null {
  const data = block
    .split(/\r?\n/)
    .filter((line) => line.startsWith("data:"))
    .map((line) => line.slice(5))
    .join("\n");
  if (!data) {
    return null;
  }
  try {
    return JSON.parse(data) as ChatEvent;
  } catch {
    return null;
  }
}
