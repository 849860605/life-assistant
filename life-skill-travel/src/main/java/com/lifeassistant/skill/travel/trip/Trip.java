package com.lifeassistant.skill.travel.trip;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 行程主表，对应 t_trip，一行是用户保存的一个行程；正文在 {@link TripVersion}，这里只放元信息和当前版本号。
 */
@Data
@TableName("t_trip")
public class Trip {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long spaceId;
    private String title;
    /** CITY / HIKING / MIXED */
    private String tripType;
    private String destination;
    /** 出发城市，例如"深圳"；可空，查机票时预填 */
    private String origin;
    private LocalDate startDate;
    private LocalDate endDate;
    /** 当前版本号，对应 t_trip_version.version；新建时为 0，首次保存后为 1 */
    private Integer currentVer;
    /** DRAFT / CONFIRMED / FINISHED */
    private String status;
    /** 出行人数，用户填，可空 */
    private Integer people;
    /** 总预算，用户填，可空；币种见 budgetCurrency */
    private BigDecimal budget;
    private String budgetCurrency;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
