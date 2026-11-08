package com.lifeassistant.skill.travel.ticket;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** 一条景点简介，对应 t_spot_intro；intro 为空是资料太少没写出来，manual 是后台改过的。 */
@Data
@TableName("t_spot_intro")
public class SpotIntro {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String city;
    private String name;
    private String intro;
    /** 用了哪些资料，如"飞猪、联网搜索"；后台改过的是"人工" */
    private String sources;
    private String model;
    private Boolean manual;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
