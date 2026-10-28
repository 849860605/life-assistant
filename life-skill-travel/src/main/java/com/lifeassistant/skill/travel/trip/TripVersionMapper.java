package com.lifeassistant.skill.travel.trip;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * t_trip_version 表的 Mapper；联合主键，只能用条件查询，不要用 selectById / updateById。
 */
@Mapper
public interface TripVersionMapper extends BaseMapper<TripVersion> {
}
