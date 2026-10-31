package com.lifeassistant.core.audit;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/** t_tool_call_log 的 Mapper：写一条调用记录，按工具或平台汇总次数、失败数和耗时。 */
@Mapper
public interface ToolCallLogMapper {

    /** 一条调用记录；input 是 JSON 字符串，写入时转成 jsonb。 */
    record Row(Long userId, String conversationId, String skillId, String kind, String toolName, String input,
               String outputDigest, Integer latencyMs, boolean success, String error) {
    }

    @Insert("""
            INSERT INTO t_tool_call_log (user_id, conversation_id, skill_id, kind, tool_name, input, output_digest,
                                         latency_ms, success, error)
            VALUES (#{userId}, #{conversationId}, #{skillId}, #{kind}, #{toolName}, CAST(#{input} AS jsonb),
                    #{outputDigest}, #{latencyMs}, #{success}, #{error})
            """)
    void insert(Row row);

    /** 最近 days 天按（类型, 名称）汇总：调用次数、失败次数、平均耗时和 P95 耗时（毫秒），按次数倒序。 */
    @Select("""
            SELECT kind, tool_name, COUNT(*) AS calls, COUNT(*) FILTER (WHERE NOT success) AS failures,
                   ROUND(AVG(latency_ms)) AS avg_ms,
                   ROUND(percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms)) AS p95_ms
            FROM t_tool_call_log
            WHERE created_at >= now() - make_interval(days => #{days})
            GROUP BY 1, 2 ORDER BY 3 DESC
            """)
    List<Map<String, Object>> stats(int days);
}
