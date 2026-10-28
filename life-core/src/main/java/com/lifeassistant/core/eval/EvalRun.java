package com.lifeassistant.core.eval;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * t_eval_run 表实体，一行是一次评测运行；result 存各用例结果的 JSON。
 */
@Data
@TableName("t_eval_run")
public class EvalRun {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String triggerBy;
    private Integer total;
    private Integer passed;
    private String result;
    private OffsetDateTime startedAt;
    private OffsetDateTime finishedAt;
}
