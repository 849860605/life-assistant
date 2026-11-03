package com.lifeassistant.skill.travel.trip;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import com.lifeassistant.core.config.LifeProperties;
import com.lifeassistant.core.place.AmapPlaceService;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 把行程导出成 A4 的 PDF：概要、住宿和各平台价格、逐日安排（带当天路线图、地点照片、两地路程）、注意事项。
 * 先拼 XHTML 再用 openhtmltopdf 排版；中文字体从 life.pdf.font-dir 加载，图片都内嵌，链接可以点。
 */
@Slf4j
@Component
public class TripPdf {

    private static final String FONT = "Noto Sans SC";
    private static final java.util.Set<String> PLACE_TYPES = java.util.Set.of("SPOT", "FOOD", "HOTEL");
    private static final Map<String, String> TYPE_NAMES = Map.of("SPOT", "景点", "FOOD", "餐饮", "HOTEL", "住宿",
            "TRANSPORT", "交通", "FREE", "自由活动");
    /** 照片下载慢的就不等了，没有照片也能导出 */
    private static final Duration PHOTO_TIMEOUT = Duration.ofSeconds(5);
    /** 单张照片最大字节数、最多下载几张 */
    private static final int MAX_PHOTO_BYTES = 2 * 1024 * 1024;
    private static final int MAX_PHOTOS = 80;

    private final AmapPlaceService places;
    private final ObjectMapper objectMapper;
    private final File fontDir;
    /** 不跟随跳转：照片地址已经限定在高德图床，跳去别处的不要 */
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public TripPdf(AmapPlaceService places, ObjectMapper objectMapper, LifeProperties properties) {
        this.places = places;
        this.objectMapper = objectMapper;
        this.fontDir = fontDir(properties.getPdf().getFontDir());
    }

    /** 相对路径按当前目录找；本地用 mvn -pl life-api 启动时当前目录是 life-api，再到上一级找。 */
    private static File fontDir(String configured) {
        File dir = new File(configured);
        File parent = new File("..", configured);
        return !dir.isAbsolute() && !dir.isDirectory() && parent.isDirectory() ? parent : dir;
    }

    /** 生成 PDF。早期只有 Markdown 正文的行程，按正文原样输出。 */
    public byte[] render(TripService.TripDetail detail) {
        File regular = new File(fontDir, "NotoSansSC-Regular.ttf");
        File bold = new File(fontDir, "NotoSansSC-Bold.ttf");
        if (!regular.isFile()) {
            throw new BizException(ErrorCode.INTERNAL, "服务器缺少中文字体，先运行 tools/fetch-fonts.sh");
        }
        String html = html(detail);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.useFont(regular, FONT, 400, BaseRendererBuilder.FontStyle.NORMAL, true);
            builder.useFont(bold.isFile() ? bold : regular, FONT, 700, BaseRendererBuilder.FontStyle.NORMAL, true);
            builder.withHtmlContent(html, null);
            // 图片都已内嵌成 data URI，不让排版引擎再去加载任何外部地址
            builder.useProtocolsStreamImplementation(url -> {
                throw new IllegalStateException("PDF 里不加载外部资源：" + url);
            }, "http", "https", "file", "ftp", "jar");
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (Exception e) {
            log.warn("行程 {} 导出 PDF 失败", detail.trip().getId(), e);
            throw new BizException(ErrorCode.INTERNAL, "PDF 生成失败，请稍后再试", e);
        }
    }

    private String html(TripService.TripDetail detail) {
        Trip trip = detail.trip();
        Itinerary it = parse(detail.version().getItinerary());
        StringBuilder h = new StringBuilder("<html><head><meta charset=\"utf-8\"/><title>").append(esc(trip.getTitle()))
                .append("</title><style>").append(CSS).append("</style></head><body>");

        // 标题和概要
        h.append("<div class=\"cover\"><div class=\"brand\">星络 · 行程单</div><h1>").append(esc(trip.getTitle())).append("</h1>")
                .append("<div class=\"meta\">").append(esc(trip.getDestination())).append(" · ")
                .append(dateLabel(str(trip.getStartDate()))).append(" – ").append(dateLabel(str(trip.getEndDate())));
        if (it != null && it.days() != null) {
            h.append(" · ").append(it.days().size()).append(" 天");
        }
        h.append(" · ").append("DRAFT".equals(trip.getStatus()) ? "草稿" : "已确认").append(" · 第 ")
                .append(detail.version().getVersion()).append(" 版</div></div>");

        if (it == null || it.days() == null) {
            h.append("<div class=\"section\">").append(markdown(detail.version().getContent())).append("</div>");
            return h.append(footer()).append("</body></html>").toString();
        }

        Map<String, String> photos = photos(it);
        String city = trip.getDestination() == null ? "" : trip.getDestination().split("[·・\\s,，]")[0];
        int food = 0;
        for (Itinerary.Day d : it.days()) {
            for (Itinerary.Item i : d.items()) {
                food += i.cost() == null ? 0 : i.cost();
            }
        }
        int stayTotal = it.stays() == null ? 0
                : it.stays().stream().mapToInt(s -> s.price() == null ? 0 : s.price() * s.nights()).sum();
        h.append("<table class=\"summary\"><tr>")
                .append(cell("景点和餐饮", food > 0 ? "约 ¥" + food + " / 人" : "—"))
                .append(cell("住宿", stayTotal > 0 ? "约 ¥" + stayTotal + " / 间" : "—"))
                .append(cell("出发", dateLabel(str(trip.getStartDate()))))
                .append(cell("返回", dateLabel(str(trip.getEndDate()))))
                .append("</tr></table>");

        // 住宿
        if (it.stays() != null && !it.stays().isEmpty()) {
            h.append("<h2>住宿</h2><table class=\"stays\"><tr><th>酒店</th><th>日期</th><th>各平台每晚价格</th><th class=\"r\">合计</th></tr>");
            for (Itinerary.Stay s : it.stays()) {
                h.append("<tr><td><div class=\"b\">").append(link(s.name(), s.url())).append(" <a class=\"amap\" href=\"")
                        .append(esc(amapUrl(s.name(), s.lng(), s.lat(), city))).append("\">高德地图</a>")
                        .append(it.isBooked(s.id()) ? " <span class=\"booked\">已预约</span>" : "").append("</div>");
                if (s.rating() != null) {
                    h.append("<div class=\"muted\">高德评分 ").append(s.rating()).append("</div>");
                }
                if (s.address() != null) {
                    h.append("<div class=\"muted\">").append(esc(s.address())).append("</div>");
                }
                h.append("</td><td class=\"nw\">").append(dateLabel(s.checkIn())).append(" 入住<br/>")
                        .append(dateLabel(s.checkOut())).append(" 离店<br/><span class=\"muted\">").append(s.nights())
                        .append(" 晚</span></td><td>");
                if (s.quotes() == null || s.quotes().isEmpty()) {
                    h.append("<span class=\"muted\">暂无价格</span>");
                } else {
                    for (Itinerary.StayQuote q : s.quotes()) {
                        h.append("<div").append(q.platform().equals(s.platform()) ? " class=\"best\"" : "").append(">")
                                .append(link(q.platform(), q.url())).append(" ¥").append(q.price()).append("</div>");
                    }
                    if (s.queriedAt() != null) {
                        h.append("<div class=\"muted\">").append(esc(queried(s.queriedAt()))).append(" 查询</div>");
                    }
                }
                h.append("</td><td class=\"r b\">").append(s.price() == null ? "—" : "¥" + s.price() * s.nights())
                        .append("</td></tr>");
            }
            h.append("</table>");
        }

        // 逐日
        for (int n = 0; n < it.days().size(); n++) {
            Itinerary.Day d = it.days().get(n);
            h.append("<div class=\"day\"><div class=\"day-head\"><h2>D").append(n + 1).append(" · ").append(dateLabel(d.date()));
            if (d.theme() != null) {
                h.append(" · ").append(esc(d.theme()));
            }
            h.append("</h2>");
            String map = dayMap(d);
            int minutes = d.legs() == null ? 0 : d.legs().stream().mapToInt(Itinerary.Leg::minutes).sum();
            double km = d.legs() == null ? 0 : d.legs().stream().mapToDouble(Itinerary.Leg::km).sum();
            if (map != null) {
                h.append("<img class=\"map\" src=\"").append(map).append("\"/>");
            }
            h.append("</div>");
            if (minutes > 0) {
                h.append("<div class=\"muted daysum\">路上合计约 ").append(minutes).append(" 分钟 · ")
                        .append(String.format(Locale.ROOT, "%.1f", km)).append(" 公里（地图上的数字是下面带编号的地点）</div>");
            }
            h.append("<table class=\"items\">");
            int stop = 0;
            for (Itinerary.Item i : d.items()) {
                boolean onMap = i.located();
                if (onMap) {
                    stop++;
                }
                h.append("<tr class=\"item\"><td class=\"time\">").append(esc(nz(i.start())));
                if (i.end() != null) {
                    h.append("<br/><span class=\"muted\">").append(esc(i.end())).append("</span>");
                }
                h.append("</td><td class=\"pic\">");
                String photo = i.photo() == null ? null : photos.get(i.photo());
                if (photo != null) {
                    h.append("<img src=\"").append(photo).append("\"/>");
                }
                h.append("</td><td><div class=\"name\">");
                if (onMap && stop <= 10) {
                    h.append("<span class=\"no\">").append(stop == 10 ? 0 : stop).append("</span> ");
                }
                h.append(link(i.name(), i.url())).append(" <span class=\"tag\">")
                        .append(esc(TYPE_NAMES.getOrDefault(i.type(), i.type()))).append("</span>");
                if (it.isBooked(i.id())) {
                    h.append(" <span class=\"booked\">已预约</span>");
                }
                if (PLACE_TYPES.contains(i.type())) {
                    h.append(" <a class=\"amap\" href=\"").append(esc(amapUrl(i.name(), i.lng(), i.lat(), city)))
                            .append("\">高德地图</a>");
                }
                h.append("</div>");
                List<String> facts = new ArrayList<>();
                if (i.rating() != null) {
                    facts.add("评分 " + i.rating());
                }
                if (i.cost() != null && i.cost() > 0) {
                    facts.add("人均 ¥" + i.cost());
                }
                if (i.address() != null) {
                    facts.add(i.address());
                }
                if (!facts.isEmpty()) {
                    h.append("<div class=\"muted\">").append(esc(String.join(" · ", facts))).append("</div>");
                }
                if (i.note() != null) {
                    h.append("<div>").append(esc(i.note())).append("</div>");
                }
                h.append("</td></tr>");
                Itinerary.Leg leg = d.legs() == null ? null
                        : d.legs().stream().filter(l -> l.from().equals(i.id())).findFirst().orElse(null);
                if (leg != null) {
                    h.append("<tr class=\"leg\"><td></td><td></td><td>↓ ")
                            .append(esc(legText(leg)))
                            .append("</td></tr>");
                }
            }
            h.append("</table></div>");
        }

        if (it.tips() != null && !it.tips().isBlank()) {
            h.append("<div class=\"tips\"><h2>注意事项</h2>").append(markdown(it.tips())).append("</div>");
        }
        return h.append(footer()).append("</body></html>").toString();
    }

    /** 当天路线图（高德静态地图，按顺序标号、画驾车路线），失败时不放图。 */
    private String dayMap(Itinerary.Day d) {
        List<double[]> stops = d.items().stream().filter(Itinerary.Item::located)
                .map(i -> new double[]{i.lng(), i.lat()}).toList();
        if (stops.isEmpty() || !places.available()) {
            return null;
        }
        try {
            byte[] png = places.staticMap(stops, stops.size() > 1, List.of(), 680, 260);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
        } catch (RuntimeException e) {
            log.warn("导出 PDF 时 {} 的地图生成失败：{}", d.date(), e.getMessage());
            return null;
        }
    }

    /** 并行下载所有地点照片（缩略图），返回 照片地址 → data URI；下载失败的不在里面。 */
    private Map<String, String> photos(Itinerary it) {
        Map<String, String> out = new ConcurrentHashMap<>();
        List<String> urls = it.days().stream().flatMap(d -> d.items().stream()).map(Itinerary.Item::photo)
                .map(TripEditor::safePhoto).filter(java.util.Objects::nonNull).distinct().limit(MAX_PHOTOS).toList();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String url : urls) {
                executor.submit(() -> {
                    try {
                        HttpResponse<java.io.InputStream> res = http.send(HttpRequest.newBuilder(URI.create(url))
                                .timeout(PHOTO_TIMEOUT).build(), HttpResponse.BodyHandlers.ofInputStream());
                        try (java.io.InputStream in = res.body()) {
                            String type = res.headers().firstValue("content-type").orElse("");
                            if (res.statusCode() != 200 || !type.matches("image/(jpeg|png|webp|gif)")) {
                                return;
                            }
                            // 多读一个字节判断是否超限，超了就不要
                            byte[] body = in.readNBytes(MAX_PHOTO_BYTES + 1);
                            if (body.length > 0 && body.length <= MAX_PHOTO_BYTES) {
                                out.put(url, "data:" + type + ";base64," + Base64.getEncoder().encodeToString(body));
                            }
                        }
                    } catch (Exception e) {
                        log.debug("照片 {} 下载失败：{}", url, e.getMessage());
                    }
                });
            }
            executor.shutdown();
            if (!executor.awaitTermination(PHOTO_TIMEOUT.toSeconds() + 3, TimeUnit.SECONDS)) {
                // 还没下完的不等了，中断掉，免得慢的连接拖住整个导出
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out;
    }

    private static String footer() {
        return "<div class=\"foot\">价格为查询时各平台的参考价，可能已经变动，以预订页为准。地图和路程来自高德。导出时间 "
                + ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + "</div>";
    }

    private static String cell(String label, String value) {
        return "<td><div class=\"muted\">" + esc(label) + "</div><div class=\"big\">" + esc(value) + "</div></td>";
    }

    private static String link(String text, String url) {
        return url == null || !url.matches("(?i)^https?://.*") ? esc(text)
                : "<a href=\"" + esc(url) + "\">" + esc(text) + "</a>";
    }

    /** 注意事项是简单的 Markdown：标题、列表、粗体，转成 HTML，其余按段落。 */
    static String markdown(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        boolean inList = false;
        for (String raw : text.split("\n")) {
            String line = raw.strip();
            boolean item = line.matches("^([-*]|\\d+\\.)\\s+.*");
            if (inList && !item) {
                out.append("</ul>");
                inList = false;
            }
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#")) {
                out.append("<h3>").append(inline(line.replaceFirst("^#+\\s*", ""))).append("</h3>");
            } else if (item) {
                if (!inList) {
                    out.append("<ul>");
                    inList = true;
                }
                out.append("<li>").append(inline(line.replaceFirst("^([-*]|\\d+\\.)\\s+", ""))).append("</li>");
            } else {
                out.append("<p>").append(inline(line)).append("</p>");
            }
        }
        if (inList) {
            out.append("</ul>");
        }
        return out.toString();
    }

    /** 行内：**粗体** 和 [文字](链接)。 */
    private static String inline(String text) {
        return esc(text).replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>")
                .replaceAll("\\[([^\\]]+)\\]\\((https?://[^)\\s]+)\\)", "<a href=\"$2\">$1</a>");
    }

    /** 高德地图里打开这个地点的链接，规则和网页上的一样：有坐标标点，没坐标按名称在城市里搜。 */
    private static String amapUrl(String name, Double lng, Double lat, String city) {
        String n = java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8);
        return lng != null && lat != null
                ? "https://uri.amap.com/marker?position=" + lng + "," + lat + "&name=" + n + "&coordinate=gaode&callnative=1&src=life-assistant"
                : "https://uri.amap.com/search?keyword=" + n + "&city="
                + java.net.URLEncoder.encode(city, java.nio.charset.StandardCharsets.UTF_8) + "&callnative=1&src=life-assistant";
    }

    /** XML 转义，openhtmltopdf 按 XHTML 解析。 */
    static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** "2026-11-27" 显示成"11月27日 周五"。 */
    private static String dateLabel(String date) {
        try {
            LocalDate d = LocalDate.parse(date);
            return d.getMonthValue() + "月" + d.getDayOfMonth() + "日 " + d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.CHINA);
        } catch (RuntimeException e) {
            return esc(nz(date));
        }
    }

    /** 查询时间转成北京时间 "11-27 21:59"。 */
    private static String queried(String iso) {
        try {
            return java.time.Instant.parse(iso).atZone(java.time.ZoneId.of("Asia/Shanghai"))
                    .format(DateTimeFormatter.ofPattern("MM-dd HH:mm")) + "（北京时间）";
        } catch (RuntimeException e) {
            return iso;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 一段路的说明：步行 x 分钟 / 地铁8号线 → 15号线 x 分钟 · ¥5 / 打车 x 分钟 · x 公里。 */
    private static String legText(Itinerary.Leg leg) {
        if (Itinerary.WALK.equals(leg.mode())) {
            return "步行 " + leg.minutes() + " 分钟";
        }
        if (Itinerary.TRANSIT.equals(leg.mode())) {
            Itinerary.Transit t = leg.transit();
            return String.join(" → ", t.lines()) + " " + leg.minutes() + " 分钟"
                    + (t.fee() == null ? "" : " · ¥" + (t.fee() % 1 == 0 ? String.valueOf(t.fee().intValue()) : t.fee()) + "/人");
        }
        return "打车 " + leg.minutes() + " 分钟 · " + leg.km() + " 公里";
    }

    private Itinerary parse(String json) {
        try {
            return json == null ? null : objectMapper.readValue(json, Itinerary.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static final String CSS = """
            @page { size: A4; margin: 14mm 13mm 16mm 13mm;
              @bottom-center { content: "第 " counter(page) " / " counter(pages) " 页"; font-family: 'Noto Sans SC'; font-size: 8pt; color: #8a94a6; } }
            body { font-family: 'Noto Sans SC'; font-size: 9.5pt; color: #1f2937; line-height: 1.5; }
            a { color: #2563eb; text-decoration: none; }
            .cover { background-color: #2563eb; color: #ffffff; padding: 14pt 16pt; border-radius: 8pt; }
            .brand { font-size: 8.5pt; color: #dbe6fd; }
            h1 { font-size: 18pt; font-weight: 700; margin: 3pt 0 4pt 0; }
            .cover .meta { font-size: 9.5pt; color: #eef3ff; }
            h2 { font-size: 12.5pt; font-weight: 700; margin: 16pt 0 6pt 0; padding-bottom: 3pt; border-bottom: 1.2pt solid #2563eb; }
            h3 { font-size: 10.5pt; font-weight: 700; margin: 8pt 0 3pt 0; }
            table { width: 100%; border-collapse: collapse; }
            .summary { margin-top: 10pt; }
            .summary td { border: 0.6pt solid #dfe5ee; padding: 6pt 8pt; width: 25%; }
            .big { font-size: 11.5pt; font-weight: 700; }
            .muted { color: #6b7280; font-size: 8.5pt; }
            .b { font-weight: 700; }
            .r { text-align: right; }
            .nw { white-space: nowrap; }
            .stays th { text-align: left; font-size: 8.5pt; color: #6b7280; font-weight: 400; padding: 4pt 6pt; border-bottom: 0.6pt solid #dfe5ee; }
            .stays td { padding: 6pt; vertical-align: top; border-bottom: 0.6pt solid #eef1f5; }
            .best { font-weight: 700; color: #c2410c; }
            .day-head { page-break-inside: avoid; }
            .map { width: 100%; border-radius: 6pt; margin-bottom: 3pt; }
            .daysum { margin-bottom: 4pt; }
            .items td { vertical-align: top; padding: 5pt 4pt; }
            .item { page-break-inside: avoid; border-top: 0.6pt solid #eef1f5; }
            .time { width: 34pt; font-weight: 700; white-space: nowrap; }
            .pic { width: 64pt; }
            .pic img { width: 60pt; height: 45pt; border-radius: 4pt; }
            .name { font-weight: 700; font-size: 10pt; }
            .no { display: inline-block; width: 12pt; height: 12pt; line-height: 12pt; text-align: center; border-radius: 6pt;
                  background-color: #2563eb; color: #ffffff; font-size: 7.5pt; }
            .amap { font-weight: 400; font-size: 7.5pt; }
            .booked { font-weight: 700; font-size: 7.5pt; color: #ffffff; background-color: #16a34a; padding: 0 3pt; border-radius: 3pt; }
            .tag { font-weight: 400; font-size: 7.5pt; color: #2563eb; background-color: #e6eefd; padding: 0 3pt; border-radius: 3pt; }
            .leg td { padding: 0 4pt 2pt 4pt; color: #6b7280; font-size: 8pt; }
            .tips ul { margin: 2pt 0; padding-left: 14pt; }
            .tips p { margin: 3pt 0; }
            .foot { margin-top: 18pt; padding-top: 6pt; border-top: 0.6pt solid #dfe5ee; color: #8a94a6; font-size: 8pt; }
            """;
}
