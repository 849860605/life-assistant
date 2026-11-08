package com.lifeassistant.skill.travel.ticket;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** t_spot_intro 表的 Mapper。 */
@Mapper
public interface SpotIntroMapper extends BaseMapper<SpotIntro> {

    /** 写入或覆盖自动生成的简介；后台改过（manual）的不覆盖。 */
    @Insert("""
            INSERT INTO t_spot_intro (city, name, intro, sources, model)
            VALUES (#{city}, #{name}, #{intro}, #{sources}, #{model})
            ON CONFLICT (city, name) DO UPDATE
               SET intro = EXCLUDED.intro, sources = EXCLUDED.sources, model = EXCLUDED.model, updated_at = now()
             WHERE t_spot_intro.manual = false
            """)
    int upsert(@Param("city") String city, @Param("name") String name, @Param("intro") String intro,
               @Param("sources") String sources, @Param("model") String model);
}
