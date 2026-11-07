-- 思考：提供方记"思考参数格式"（各家开关思考、调深度的写法不同），模型记开关和深度。
-- thinking_style：QWEN（enable_thinking + thinking_budget）、TYPE（thinking.type，DeepSeek/智谱/豆包）、
--                 OPENAI（reasoning_effort）、CUSTOM（关闭时带 no_thinking 里的 JSON）、NONE（不支持，什么都不带）
ALTER TABLE t_llm_provider ADD COLUMN thinking_style VARCHAR(16);
-- thinking_level：LOW / MEDIUM / HIGH / CUSTOM（自定义时用 thinking_budget，单位 Token）
ALTER TABLE t_llm_model ADD COLUMN thinking BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE t_llm_model ADD COLUMN thinking_level VARCHAR(8) NOT NULL DEFAULT 'MEDIUM';
ALTER TABLE t_llm_model ADD COLUMN thinking_budget INT;

UPDATE t_llm_provider SET thinking_style = 'QWEN' WHERE code = 'qwen';
UPDATE t_llm_provider SET thinking_style = 'TYPE' WHERE code IN ('deepseek', 'zhipu', 'doubao');
UPDATE t_llm_provider SET thinking_style = 'OPENAI' WHERE code = 'openai';
UPDATE t_llm_provider SET thinking_style = CASE WHEN no_thinking IS NOT NULL THEN 'CUSTOM' ELSE 'NONE' END
WHERE thinking_style IS NULL;
