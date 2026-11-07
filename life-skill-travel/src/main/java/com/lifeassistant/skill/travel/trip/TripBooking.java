package com.lifeassistant.skill.travel.trip;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 行程的一条预订（机票、酒店、门票等），对应 t_trip_booking。source 区分手动录入、凭证导入和平台同步，
 * 用户自己标记的"已预订"不等于平台确认。
 */
@Data
@TableName("t_trip_booking")
public class TripBooking {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tripId;
    @JsonIgnore
    private Long userId;
    /** FLIGHT / HOTEL / TICKET / OTHER */
    private String kind;
    private String title;
    private String platform;
    private OffsetDateTime startAt;
    private OffsetDateTime endAt;
    private Integer people;
    private BigDecimal amount;
    private String currency;
    private String bookingNo;
    private String location;
    /** PLANNED 打算订 / BOOKED 已订（用户标记）/ CANCELLED 已取消 */
    private String status;
    /** MANUAL 手动录入 / IMPORT 凭证导入 / SYNC 平台同步 */
    private String source;
    /** 凭证文件 id，逗号分隔 */
    @JsonIgnore
    private String fileIds;
    private String note;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
