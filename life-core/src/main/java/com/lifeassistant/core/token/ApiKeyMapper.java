package com.lifeassistant.core.token;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** t_api_key 访问令牌的 Mapper。 */
@Mapper
public interface ApiKeyMapper extends BaseMapper<ApiKey> {
}
