package com.lifeassistant.core.brief;

import java.util.Optional;

/** 简报里的一段，各模块各自实现（天气、日程在核心模块，行程在出游技能）；没内容时返回空，这段就不出现。 */
public interface BriefSection {

    /** 生成和显示的顺序，小的在前 */
    int order();

    Optional<Brief.Section> build(Brief.Context context);
}
