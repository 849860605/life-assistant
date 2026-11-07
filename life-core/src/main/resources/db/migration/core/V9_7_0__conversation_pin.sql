-- 置顶对话：pinned_at 不为空的排在最前，按置顶时间倒序
ALTER TABLE t_conversation ADD COLUMN pinned_at TIMESTAMPTZ;
