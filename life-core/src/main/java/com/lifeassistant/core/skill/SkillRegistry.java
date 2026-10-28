package com.lifeassistant.core.skill;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 技能注册表：收集所有 LifeSkill Bean，按 id 查找。 */
@Component
public class SkillRegistry {

    private final Map<String, LifeSkill> skills = new LinkedHashMap<>();

    /** 按注入顺序登记；id 重复或缺少兜底技能 general 时启动失败。 */
    public SkillRegistry(List<LifeSkill> skills) {
        skills.forEach(skill -> {
            if (this.skills.put(skill.id(), skill) != null) {
                throw new IllegalStateException("技能 id 重复：" + skill.id());
            }
        });
        if (!this.skills.containsKey(GeneralSkill.ID)) {
            throw new IllegalStateException("缺少兜底技能 general");
        }
    }

    /** 找不到时退回通用技能，不会返回 null。 */
    public LifeSkill get(String id) {
        return skills.getOrDefault(id, skills.get(GeneralSkill.ID));
    }

    /** 用来校验路由结果里的技能 id 是否真实存在（get 会静默兜底，不能用来判断）。 */
    public boolean contains(String id) {
        return skills.containsKey(id);
    }

    /** 按注册顺序返回全部技能。 */
    public Collection<LifeSkill> all() {
        return skills.values();
    }
}
