package com.lifeassistant.skill.travel.flight;

import com.lifeassistant.common.error.BizException;
import com.lifeassistant.core.audit.CallRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** 对话里查航班实时动态：和页面上一样，免费用户扣试查次数，用完提示开通会员。 */
@Component
@RequiredArgsConstructor
public class FlightStatusTools {

    private final FlightStatusService statusService;

    @Tool(description = """
            查某个航班的实时动态（飞常准）：状态（计划、起飞、到达、延误、取消）、计划/预计/实际起降时间、航站楼、
            值机柜台、登机口、行李转盘、准点率、延误原因。用户问"CZ3101 现在什么情况""登机口在哪""延误了吗"时用。
            登机口一般起飞前一两个小时才定，提前查不到是正常的。免费用户次数有限，返回 error 时把原话告诉用户。""")
    public Map<String, Object> flightStatus(
            @ToolParam(description = "航班号，例如 CZ3101") String flightNo,
            @ToolParam(description = "日期 yyyy-MM-dd，不填是今天", required = false) String date) {
        CallRecorder.Scope scope = CallRecorder.current();
        if (scope == null || scope.userId() == null) {
            return Map.of("error", "没有登录信息，查不了");
        }
        try {
            LocalDate day = date == null || date.isBlank() ? null : LocalDate.parse(date.trim());
            FlightStatusService.Result r = statusService.query(scope.userId(), flightNo, day);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", r.status());
            if (!r.usage().member()) {
                out.put("trialLeft", r.usage().left());
                out.put("hint", "免费用户还能试查 " + r.usage().left() + " 次，开通会员可以随时查看，出发当天自动提醒延误和登机口变化");
            }
            return out;
        } catch (BizException e) {
            return Map.of("error", e.getMessage());
        } catch (RuntimeException e) {
            return Map.of("error", "日期格式不对，要 yyyy-MM-dd");
        }
    }
}
