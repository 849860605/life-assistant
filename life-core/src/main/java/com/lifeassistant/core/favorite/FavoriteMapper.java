package com.lifeassistant.core.favorite;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** t_favorite 表的 Mapper，只用 MyBatis-Plus 自带的 CRUD。 */
@Mapper
public interface FavoriteMapper extends BaseMapper<Favorite> {
}
