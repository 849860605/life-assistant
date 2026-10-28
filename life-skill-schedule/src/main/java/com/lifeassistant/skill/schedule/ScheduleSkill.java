package com.lifeassistant.skill.schedule;

import com.lifeassistant.core.schedule.ScheduleTools;
import com.lifeassistant.core.skill.LifeSkill;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 日程技能：日程的对话入口。日程存储和提醒调度在 life-core，其他技能也能直接写日程。
 */
@Component
@RequiredArgsConstructor
public class ScheduleSkill implements LifeSkill {

    private final ScheduleTools scheduleTools;

    /** 技能 id，路由结果用它定位技能。 */
    @Override
    public String id() {
        return "schedule";
    }

    /** 中文名，给前端和日志看。 */
    @Override
    public String name() {
        return "日程";
    }

    /** 给路由 Agent 看的职责说明。 */
    @Override
    public String description() {
        return "日程和提醒：新建、查询、删除日程，设置到点推送提醒，问某天有什么安排";
    }

    /** 示例问法，提高路由准确率，也是评测集的种子。 */
    @Override
    public List<String> routingExamples() {
        return List.of("明天下午三点提醒我去取快递", "这周六我有什么安排", "把周五的聚餐删掉");
    }

    /** 日程提示词：相对日期换算、默认提醒提前量、删除前先确认、创建后复述。 */
    @Override
    public String systemPrompt() {
        return """
                你现在负责日程和提醒。
                - "明天""下周五"这类说法按当前时间换算成具体日期，时间不明确时先问。
                - 用户说"提醒我"时设置提醒；没说提前多久，默认提前 15 分钟，出远门的默认提前 60 分钟。
                - 删除日程前先用 listEvents 找到是哪一条，向用户确认后再删。
                - 创建成功后用一句话复述：什么时间、什么事、几点提醒。""";
    }

    /** 只挂 life-core 的日程工具。 */
    @Override
    public List<Object> tools() {
        return List.of(scheduleTools);
    }
}
