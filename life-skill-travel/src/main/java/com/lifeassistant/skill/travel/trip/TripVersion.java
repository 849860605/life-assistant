package com.lifeassistant.skill.travel.trip;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonRawValue;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 行程版本，对应 t_trip_version 表，一行是某个行程的一次保存（完整正文快照）。
 * 主键是 (trip_id, version)，MyBatis-Plus 不支持联合主键，只用条件查询。
 */
@Data
@TableName("t_trip_version")
public class TripVersion {
    private Long tripId;
    private Integer version;
    /** 行程正文，Markdown */
    private String content;
    /** 结构化行程（Itinerary 的 JSON），库里是 JSONB；早期保存的行程没有 */
    @JsonRawValue
    private String itinerary;
    private String changeNote;
    /** USER 或 AI */
    private String createdBy;
    private OffsetDateTime createdAt;
}
