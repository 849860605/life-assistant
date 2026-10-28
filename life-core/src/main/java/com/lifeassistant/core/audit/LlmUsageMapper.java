package com.lifeassistant.core.audit;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/** t_llm_usage 的 Mapper，附带按天汇总的统计查询。 */
@Mapper
public interface LlmUsageMapper extends BaseMapper<LlmUsage> {

    /** 最近 days 天按（北京时间日期, 模型）汇总的 Token 数和调用次数，日期倒序。 */
    @Select("""
            SELECT to_char(created_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD') AS day, model,
                   SUM(prompt_tokens) AS prompt_tokens, SUM(completion_tokens) AS completion_tokens, COUNT(*) AS calls
            FROM t_llm_usage
            WHERE created_at >= now() - make_interval(days => #{days})
            GROUP BY 1, 2 ORDER BY 1 DESC, 2
            """)
    List<Map<String, Object>> dailySummary(int days);
}
