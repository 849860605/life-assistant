package com.lifeassistant.core.favorite;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.OffsetDateTime;

/** 一条收藏，对应 t_favorite：kind 是 PLAN 方案 / HOTEL 酒店 / FOOD 美食 / SPOT 景点，refKey 用来去重。 */
@Data
@TableName("t_favorite")
public class Favorite {
    @TableId(type = IdType.AUTO)
    private Long id;
    @JsonIgnore
    private Long userId;
    private String kind;
    private String refKey;
    private String title;
    private String subtitle;
    private String city;
    private String url;
    private String photo;
    private OffsetDateTime createdAt;
}
