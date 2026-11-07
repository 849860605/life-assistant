package com.lifeassistant.skill.travel.today;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** t_user_address 表的 Mapper，只用 MyBatis-Plus 自带的 CRUD。 */
@Mapper
public interface UserAddressMapper extends BaseMapper<UserAddress> {
}
