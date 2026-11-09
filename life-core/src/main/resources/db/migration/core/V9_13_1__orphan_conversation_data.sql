-- 以前删会话时没删对话断点和工具调用记录，里面还留着对话内容：一次性清掉已经不存在的会话留下的
DELETE FROM t_tool_call_log
WHERE conversation_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM t_conversation c WHERE c.id = t_tool_call_log.conversation_id);
DO $$
BEGIN
    IF to_regclass('graphthread') IS NOT NULL THEN
        DELETE FROM graphthread g WHERE NOT EXISTS (SELECT 1 FROM t_conversation c WHERE c.id = g.thread_name);
    END IF;
END $$;
