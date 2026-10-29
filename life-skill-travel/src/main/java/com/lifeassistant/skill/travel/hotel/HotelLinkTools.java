package com.lifeassistant.skill.travel.hotel;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 酒店查价跳转链接。拿不到实时价格（没有开放接口，也不爬），只生成跳到各平台的链接，价格让用户在平台上看。
 * 链接格式都集中在这里，平台改版导致失效时只改这个类。
 */
@Component
public class HotelLinkTools {

    private static final DateTimeFormatter CTRIP_DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    /** 一家酒店的各平台链接：高德是官方 URI 接口；携程、美团是网站搜索页，不是公开接口，可能随改版失效。 */
    @Tool(description = """
            给一家酒店生成查价链接（高德、携程、美团）。用地图工具选好候选酒店后，每家调用一次，
            在回答里用 Markdown 链接列出来，让用户点开查实时价格。不要自己编价格，也不要自己拼链接。""")
    public Map<String, String> hotelLinks(
            @ToolParam(description = "酒店名称，用地图工具返回的完整名称") String hotelName,
            @ToolParam(description = "所在城市，例如：泰安") String city,
            @ToolParam(description = "经度，来自地图工具；没有就不填", required = false) Double lon,
            @ToolParam(description = "纬度，来自地图工具；没有就不填", required = false) Double lat,
            @ToolParam(description = "入住日期 yyyy-MM-dd，不知道就不填", required = false) String checkIn,
            @ToolParam(description = "离店日期 yyyy-MM-dd，不知道就不填", required = false) String checkOut) {
        return links(hotelName, city, lon, lat, parse(checkIn), parse(checkOut));
    }

    /** 拼三个平台的链接；有坐标才给高德，日期不全时携程不带日期。 */
    static Map<String, String> links(String hotelName, String city, Double lon, Double lat,
                                     LocalDate checkIn, LocalDate checkOut) {
        String keyword = (city == null || hotelName.contains(city) ? "" : city + " ") + hotelName;
        Map<String, String> links = new LinkedHashMap<>();
        if (lon != null && lat != null) {
            // 高德 URI API：手机上会唤起高德 App，酒店页里能看到可订价格
            links.put("高德", "https://uri.amap.com/marker?position="
                    + String.format(Locale.ROOT, "%.6f,%.6f", lon, lat)
                    + "&name=" + encode(hotelName) + "&src=life-assistant&coordinate=gaode&callnative=1");
        }
        String ctrip = "https://hotels.ctrip.com/hotels/list?keyword=" + encode(keyword);
        if (checkIn != null && checkOut != null && checkOut.isAfter(checkIn)) {
            ctrip += "&checkin=" + CTRIP_DATE.format(checkIn) + "&checkout=" + CTRIP_DATE.format(checkOut);
        }
        links.put("携程", ctrip);
        links.put("美团", "https://i.meituan.com/s/" + encode(keyword));
        return links;
    }

    /** yyyy-MM-dd，空或格式不对返回 null（日期只是锦上添花，不让整个工具失败）。 */
    private static LocalDate parse(String text) {
        try {
            return text == null || text.isBlank() ? null : LocalDate.parse(text.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** URL 编码，空格编成 %20 而不是 +，各平台都能识别。 */
    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
