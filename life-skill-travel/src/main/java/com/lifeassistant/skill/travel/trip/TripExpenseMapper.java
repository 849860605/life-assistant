package com.lifeassistant.skill.travel.trip;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** t_trip_expense 表的 Mapper，只用 MyBatis-Plus 自带的 CRUD。 */
@Mapper
public interface TripExpenseMapper extends BaseMapper<TripExpense> {
}
