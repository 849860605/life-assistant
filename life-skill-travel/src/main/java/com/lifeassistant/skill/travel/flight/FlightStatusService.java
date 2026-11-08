package com.lifeassistant.skill.travel.flight;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import com.lifeassistant.core.member.FeatureTrial;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 航班实时动态（飞常准）：状态、计划/预计/实际起降、值机柜台、登机口、行李转盘、准点率等。
 * 同一航班同一天 5 分钟内的结果走缓存（所有用户共用），走缓存不扣试用次数；
 * 免费用户总共能试查几次（后台可改），会员不限；查询失败或没查到时退回这次试用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlightStatusService {

    private static final Duration CACHE = Duration.ofMinutes(5);
    private static final Pattern FLIGHT_NO = Pattern.compile("[A-Z0-9]{2}\\d{1,4}");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final VariflightClient client;
    private final FeatureTrial trial;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /**
     * 一个航班的动态。时间都是"yyyy-MM-dd HH:mm"（当地时间），没有为空：plan 计划、estimate 预计、actual 实际。
     * exitMinutes 是落地后到出机场大约多少分钟（如"25～37"）。
     */
    public record FlightStatus(String flightNo, String airline, String state, String depCity, String arrCity,
                               String depAirport, String arrAirport, String depTerminal, String arrTerminal,
                               String planDep, String planArr, String estimateDep, String estimateArr,
                               String actualDep, String actualArr, String checkinCounters, String securityGate,
                               String boardGate, String boardStart, String boardEnd, String lastCheckin,
                               String baggage, String onTimeRate, String aircraft, String delayReason,
                               String exitMinutes, String queriedAt) {
    }

    /** 查询结果：动态、这次用了缓存没有、试用情况（会员时 member 为 true）。 */
    public record Result(FlightStatus status, boolean cached, FeatureTrial.Usage usage) {
    }

    public boolean available() {
        return client.available();
    }

    /** 当前用户查一个航班某天的动态。 */
    public Result query(long userId, String flightNo, LocalDate date) {
        String no = flightNo == null ? "" : flightNo.replaceAll("\\s+", "").toUpperCase();
        if (!FLIGHT_NO.matcher(no).matches()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "航班号不对，例如 CZ3101");
        }
        if (date == null) {
            date = LocalDate.now(ZONE);
        }
        if (!client.available()) {
            throw new BizException(ErrorCode.INTERNAL, "航班动态还没开通（缺飞常准的 Key）");
        }
        String key = "flightstatus:" + no + ":" + date;
        FlightStatus cached = read(key);
        if (cached != null) {
            return new Result(cached, true, trial.usage(userId, FeatureTrial.FLIGHT_STATUS));
        }
        FeatureTrial.Usage usage = trial.consume(userId, FeatureTrial.FLIGHT_STATUS, "航班实时动态");
        FlightStatus status;
        try {
            JsonNode data = client.call("searchFlightsByNumber", Map.of("fnum", no, "date", date.toString()));
            JsonNode f = data.isArray() ? data.path(0) : data;
            if (f.isMissingNode() || f.isEmpty()) {
                throw new BizException(ErrorCode.NOT_FOUND, "没查到 " + no + " 在 " + date + " 的航班，核对一下航班号和日期");
            }
            status = parse(f);
        } catch (RuntimeException e) {
            trial.refund(userId, FeatureTrial.FLIGHT_STATUS);
            if (e instanceof BizException biz) {
                throw biz;
            }
            log.warn("查航班动态失败 {} {}：{}", no, date, e.getMessage());
            throw new BizException(ErrorCode.EXTERNAL_SERVICE, "航班动态这会儿查不到，稍后再试（这次不算次数）");
        }
        write(key, status);
        return new Result(status, false, usage.member() ? usage : trial.usage(userId, FeatureTrial.FLIGHT_STATUS));
    }

    /** 飞常准的字段转成 FlightStatus。 */
    static FlightStatus parse(JsonNode f) {
        String exit = f.path("FastestExitDuration").isNumber() && f.path("SlowestExitDuration").isNumber()
                ? Math.round(f.path("FastestExitDuration").asDouble()) + "～" + Math.round(f.path("SlowestExitDuration").asDouble()) : null;
        String estimateArr = text(f, "VeryZhunReadyArrtimeDate");
        return new FlightStatus(text(f, "FlightNo"), text(f, "FlightCompany"), text(f, "FlightState"),
                text(f, "FlightDep"), text(f, "FlightArr"), text(f, "FlightDepAirport"), text(f, "FlightArrAirport"),
                text(f, "FlightHTerminal"), text(f, "FlightTerminal"),
                time(f, "FlightDeptimePlanDate"), time(f, "FlightArrtimePlanDate"),
                time(f, "FlightDeptimeReadyDate"), estimateArr != null ? cut(estimateArr) : time(f, "FlightArrtimeReadyDate"),
                time(f, "FlightDeptimeDate"), time(f, "FlightArrtimeDate"),
                text(f, "CheckinTable"), text(f, "CheckDoor"), text(f, "BoardGate"),
                time(f, "EstimateBoardingStartTime"), time(f, "EstimateBoardingEndTime"), time(f, "LastCheckinTime"),
                text(f, "BaggageID"), text(f, "OntimeRate"), text(f, "generic"), text(f, "DelayReason"), exit,
                OffsetDateTime.now(ZONE).toString().substring(0, 16).replace('T', ' '));
    }

    private static String text(JsonNode f, String field) {
        String v = f.path(field).asText("").trim();
        return v.isEmpty() || "null".equals(v) ? null : v;
    }

    /** "2026-11-08 08:00:00" 取到分钟 */
    private static String time(JsonNode f, String field) {
        String v = text(f, field);
        return v == null ? null : cut(v);
    }

    private static String cut(String v) {
        return v.length() >= 16 ? v.substring(0, 16) : v;
    }

    private FlightStatus read(String key) {
        try {
            String json = redis.opsForValue().get(key);
            return json == null ? null : objectMapper.readValue(json, FlightStatus.class);
        } catch (Exception e) {
            return null;
        }
    }

    private void write(String key, FlightStatus status) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(status), CACHE);
        } catch (Exception e) {
            log.warn("航班动态写缓存失败：{}", e.getMessage());
        }
    }
}
