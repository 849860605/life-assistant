package com.lifeassistant.core.card;

/**
 * 工具执行中的一条进度，前端显示在回答顶部："查询酒店并比价 · 查网上口碑 4/10"。
 * id 区分同一轮里同时跑的几条进度，同一个 id 的新进度覆盖旧的；done 表示这一步结束了。只推给前端，不存库。
 */
public record Progress(String id, String title, String detail, boolean done) {
}
