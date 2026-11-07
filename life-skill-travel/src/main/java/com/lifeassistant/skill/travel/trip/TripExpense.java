package com.lifeassistant.skill.travel.trip;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 行程的一笔实际支出，对应 t_trip_expense。bookingId 关联一条预订，一条预订只能记一笔，避免和预订金额重复计算。
 */
@Data
@TableName("t_trip_expense")
public class TripExpense {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tripId;
    @JsonIgnore
    private Long userId;
    /** TRANSPORT 交通 / STAY 住宿 / FOOD 餐饮 / TICKET 门票 / SHOPPING 购物 / OTHER 其他 */
    private String category;
    private String title;
    private BigDecimal amount;
    private String currency;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDate spentOn;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long bookingId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String note;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
