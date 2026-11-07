-- 千问（百炼）：走 OpenAI 兼容接口，关掉思考（千问 3.5 默认先思考，慢好几倍）。Key 不放在这里，到后台「模型配置」里填。
-- 只给已经有模型配置的库补上；全新安装时表是空的，程序会先从 application.yml 导入，这里什么都不做。
INSERT INTO t_llm_provider (code, name, type, base_url, completions_path, no_thinking, enabled, sort)
SELECT 'qwen', '千问（百炼）', 'openai', 'https://dashscope.aliyuncs.com/compatible-mode', '/v1/chat/completions',
       '{"enable_thinking": false}', TRUE, 1
WHERE EXISTS (SELECT 1 FROM t_llm_provider)
ON CONFLICT (code) DO NOTHING;

-- 三个模型先不给 App 用户选，后台填好 Key、测试通过后再打开
INSERT INTO t_llm_model (provider_code, model, display_name, description, enabled, user_selectable, supports_tools,
                         price_input, price_output, sort)
SELECT v.provider_code, v.model, v.display_name, v.description, TRUE, FALSE, TRUE, v.price_input, v.price_output, v.sort
FROM (VALUES ('qwen', 'qwen3.5-flash', '千问 3.5 Flash', '千问，速度快、能看图', 0.2, 2.0, 1),
             ('qwen', 'qwen3.5-plus', '千问 3.5 Plus', '千问，规划更细', 0.8, 4.8, 2),
             ('qwen', 'qwen-flash', '千问 Flash（小任务）', '路由、打分、追问建议', 0.15, 1.5, 3))
         AS v(provider_code, model, display_name, description, price_input, price_output, sort)
WHERE EXISTS (SELECT 1 FROM t_llm_provider WHERE code = 'qwen')
ON CONFLICT (provider_code, model) DO NOTHING;
