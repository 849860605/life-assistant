package com.lifeassistant.skill.travel.ticket;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lifeassistant.core.audit.UsageRecorder;
import com.lifeassistant.core.llm.ChatModelRegistry;
import com.lifeassistant.core.llm.ModelSpec;
import com.lifeassistant.core.search.BochaSearchProvider;
import com.lifeassistant.core.search.WebSearchProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 景点详情：飞猪上的景点信息（几 A、榜单、照片、是否免费）加一段两三句的简介。
 * 简介先查资料（飞猪景点简介，太短时再联网搜一次），再让快模型只根据资料整理，不编年代、人物、数字，
 * 不写开放时间和门票（那些只显示接口查到的）。按"城市 + 景点名"存库，所有用户共用，每个景点只生成一次；
 * 资料太少写不出来时也记一笔，7 天内不再重试。后台改过的简介不会被自动覆盖。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpotIntroService {

    /** 资料太少时多久后再试 */
    private static final java.time.Duration RETRY_EMPTY = java.time.Duration.ofDays(7);
    /** 飞猪简介够这么长就不再联网搜 */
    private static final int ENOUGH = 60;
    private static final String PROMPT = """
            你在给旅行 App 写景点简介。只根据用户给的资料，写 2～3 句、不超过 110 个字：这是什么地方、最值得看的是什么、适合怎么玩。
            规则：
            - 资料里没有的事实不写，年代、人物、数字尤其不能编；资料之间矛盾的不写。
            - 不写开放时间、门票价格、预约方式、交通方式。
            - 不用"打卡""不容错过""必去""网红"这类营销词，不加标题、不用列表、不用 emoji。
            - 资料说不清这是什么地方，或者资料讲的是别的同名地点时，只输出：无
            """;

    private final SpotIntroMapper mapper;
    private final TicketSearch ticketSearch;
    private final BochaSearchProvider bocha;
    private final ChatModelRegistry chatModels;
    private final UsageRecorder usageRecorder;
    /** 同一个景点同时只生成一次（几个人同时打开时） */
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    /**
     * 景点详情：spot 是飞猪上对上的景点（对不上为空），intro 是简介（写不出来为空），aiWritten 为 true 表示简介是
     * AI 根据资料整理的（后台改过的为 false）。
     */
    public record Detail(String name, TicketSearch.Spot spot, String intro, boolean aiWritten) {
    }

    public Detail detail(String city, String spot, Long userId) {
        String c = city == null ? "" : city.trim();
        String name = TicketSearch.shortName(spot);
        if (name.isBlank() || name.length() > 30 || c.length() > 30) {
            throw new com.lifeassistant.common.error.BizException(com.lifeassistant.common.error.ErrorCode.BAD_REQUEST, "景点名称不对");
        }
        TicketSearch.Spot found = fliggySpot(c, name);
        SpotIntro saved = find(c, name);
        if (saved == null || stale(saved)) {
            synchronized (locks.computeIfAbsent(c + "|" + name, k -> new Object())) {
                saved = find(c, name);
                if (saved == null || stale(saved)) {
                    saved = generate(c, name, found, userId);
                }
            }
        }
        String intro = saved == null ? null : saved.getIntro();
        return new Detail(name, found, intro, intro != null && !Boolean.TRUE.equals(saved.getManual()));
    }

    /** 资料太少没写出来、已经过了 7 天的，再试一次 */
    private static boolean stale(SpotIntro s) {
        return s.getIntro() == null && !Boolean.TRUE.equals(s.getManual())
                && s.getUpdatedAt() != null && s.getUpdatedAt().isBefore(OffsetDateTime.now().minus(RETRY_EMPTY));
    }

    private SpotIntro find(String city, String name) {
        return mapper.selectOne(new LambdaQueryWrapper<SpotIntro>().eq(SpotIntro::getCity, city).eq(SpotIntro::getName, name));
    }

    /** 飞猪上按名字搜这个景点，名字对得上的才算（搜"爱晚亭"可能给出整个岳麓山）；飞猪没配置、查失败为空。 */
    private TicketSearch.Spot fliggySpot(String city, String name) {
        if (city.isBlank()) {
            return null;
        }
        try {
            List<TicketSearch.Spot> list = ticketSearch.spots(city, name, null);
            for (TicketSearch.Spot s : list) {
                if (name.equals(TicketSearch.shortName(s.name()))) {
                    return s;
                }
            }
            for (TicketSearch.Spot s : list) {
                String n = TicketSearch.shortName(s.name());
                if (n.length() >= 2 && (n.contains(name) || name.contains(n))) {
                    return s;
                }
            }
        } catch (RuntimeException e) {
            log.info("飞猪查景点 {} {} 失败：{}", city, name, e.getMessage());
        }
        return null;
    }

    /** 查资料、让模型整理，存库后返回；没资料或模型说写不出来时存一条空的。 */
    private SpotIntro generate(String city, String name, TicketSearch.Spot spot, Long userId) {
        StringBuilder material = new StringBuilder();
        List<String> sources = new ArrayList<>();
        if (spot != null) {
            if (spot.description() != null && !spot.description().isBlank()) {
                material.append("飞猪简介：").append(spot.description()).append('\n');
                sources.add("飞猪");
            }
            if (spot.level() != null) {
                material.append("等级：").append(spot.level()).append("A 级景区\n");
            }
            if (spot.rank() != null) {
                material.append("榜单：").append(spot.rank()).append('\n');
            }
            if (spot.category() != null) {
                material.append("类别：").append(spot.category()).append('\n');
            }
        }
        if ((spot == null || spot.description() == null || spot.description().length() < ENOUGH) && bocha.available()) {
            try {
                List<WebSearchProvider.SearchResult> found = bocha.search(city + name + " 景点介绍", List.of(), 5, false);
                int n = 0;
                for (WebSearchProvider.SearchResult r : found) {
                    String text = r.summary() != null && !r.summary().isBlank() ? r.summary() : r.snippet();
                    if (text == null || text.isBlank()) {
                        continue;
                    }
                    material.append("网上资料").append(++n).append("（").append(r.title()).append("）：")
                            .append(text.length() > 300 ? text.substring(0, 300) : text).append('\n');
                }
                if (n > 0) {
                    sources.add("联网搜索");
                }
            } catch (RuntimeException e) {
                log.info("联网搜景点 {} {} 失败：{}", city, name, e.getMessage());
            }
        }
        String intro = null;
        String modelId = null;
        if (!sources.isEmpty()) {
            try {
                ModelSpec model = chatModels.resolve(chatModels.models().getRouter());
                modelId = model.id();
                ChatResponse response = chatModels.quickClient(model).prompt()
                        .system(PROMPT)
                        .user("景点：" + name + (city.isBlank() ? "" : "（" + city + "）") + "\n" + material)
                        .options(ChatModelRegistry.options(model, 0.2))
                        .call().chatResponse();
                if (response != null) {
                    usageRecorder.record(userId, null, "SPOT_INTRO", model.id(), response.getMetadata().getUsage());
                    intro = clean(response.getResult() == null ? null : response.getResult().getOutput().getText());
                }
            } catch (RuntimeException e) {
                // 模型出错不存，下次打开再试
                log.warn("整理景点简介 {} {} 失败：{}", city, name, e.getMessage());
                return null;
            }
        }
        mapper.upsert(city, name, intro, String.join("、", sources), modelId);
        return find(city, name);
    }

    /** 去掉模型多给的引号、标题；说写不出来（"无"）或太短的当没有。 */
    static String clean(String text) {
        if (text == null) {
            return null;
        }
        String t = text.strip().replaceAll("^[#*\\s]+", "").replaceAll("^[\"“]|[\"”]$", "").strip();
        if (t.length() < 12 || t.startsWith("无")) {
            return null;
        }
        return t.length() > 200 ? t.substring(0, 200) : t;
    }

    /** 后台：按名称搜简介（最近更新的在前）。 */
    public List<SpotIntro> list(String keyword, int limit) {
        LambdaQueryWrapper<SpotIntro> q = new LambdaQueryWrapper<SpotIntro>().orderByDesc(SpotIntro::getUpdatedAt)
                .last("LIMIT " + Math.max(1, Math.min(limit, 200)));
        if (keyword != null && !keyword.isBlank()) {
            q.and(w -> w.like(SpotIntro::getName, keyword.trim()).or().like(SpotIntro::getCity, keyword.trim()));
        }
        return mapper.selectList(q);
    }

    /** 后台改简介：改过的标为人工，不再自动重写；改成空就是"不显示简介"。 */
    public SpotIntro edit(long id, String intro) {
        SpotIntro s = mapper.selectById(id);
        if (s == null) {
            throw new com.lifeassistant.common.error.BizException(com.lifeassistant.common.error.ErrorCode.NOT_FOUND, "没有这条简介");
        }
        String t = intro == null || intro.isBlank() ? null : intro.strip();
        if (t != null && t.length() > 400) {
            throw new com.lifeassistant.common.error.BizException(com.lifeassistant.common.error.ErrorCode.BAD_REQUEST, "简介最多 400 字");
        }
        s.setIntro(t);
        s.setManual(true);
        s.setSources("人工");
        s.setUpdatedAt(OffsetDateTime.now());
        mapper.updateById(s);
        return s;
    }

    /** 后台删掉一条：下次有人打开这个景点时重新生成。 */
    public SpotIntro delete(long id) {
        SpotIntro s = mapper.selectById(id);
        if (s != null) {
            mapper.deleteById(id);
        }
        return s;
    }
}
