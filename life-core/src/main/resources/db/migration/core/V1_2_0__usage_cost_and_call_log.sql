-- 用量记录加上缓存命中数和费用；调用日志区分模型发起的工具调用（TOOL）和工具内部的平台接口调用（PLATFORM）

-- 缓存命中的输入 Token 数；为空表示接口没返回（多轮工具调用合并后的用量拿不到）
ALTER TABLE t_llm_usage ADD COLUMN cached_tokens INT;
-- 费用（元），按 life.llm.pricing 的单价算；没配单价的模型为空
ALTER TABLE t_llm_usage ADD COLUMN cost NUMERIC(12, 6);

ALTER TABLE t_tool_call_log ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'TOOL';
CREATE INDEX idx_tool_call_day ON t_tool_call_log (created_at);
