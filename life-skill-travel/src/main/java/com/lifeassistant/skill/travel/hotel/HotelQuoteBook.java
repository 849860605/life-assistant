package com.lifeassistant.skill.travel.hotel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeassistant.core.conversation.ChatMessageEntity;
import com.lifeassistant.core.conversation.ChatMessageMapper;
import com.lifeassistant.core.place.AmapPlaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 记下每个会话最近几次酒店比价的结果。保存行程时按酒店名找回各平台报价和其他候选，
 * 价格和链接就不用经过模型转写。先找内存；后端重启过内存里没有时，从会话消息里存的酒店比价卡片找。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HotelQuoteBook {

    private static final int MAX_CONVERSATIONS = 200;
    private static final int MAX_PER_CONVERSATION = 5;

    /** 一次比价：城市、日期、查询时间，以及每家酒店的各平台报价（按比价时的顺序）。 */
    public record Comparison(String city, String checkIn, String checkOut, String queriedAt, List<Entry> hotels) {
    }

    public record Entry(String hotel, List<HotelPriceSource.Quote> quotes) {
    }

    /** 找到的那家酒店和它所在的那次比价。 */
    public record Found(Comparison comparison, Entry entry) {
    }

    /** 从消息里找比价卡片时最多往前看这么多条 */
    private static final int HISTORY_MESSAGES = 60;

    private final ChatMessageMapper messageMapper;
    private final ObjectMapper objectMapper;
    private final Map<String, List<Comparison>> byConversation = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<Comparison>> eldest) {
                    return size() > MAX_CONVERSATIONS;
                }
            });

    /** 记一次比价；没有会话（比如页面上直接查）时不记。 */
    public void record(String conversationId, Comparison comparison) {
        if (conversationId == null) {
            return;
        }
        List<Comparison> list = byConversation.computeIfAbsent(conversationId, k -> new ArrayList<>());
        synchronized (list) {
            list.add(comparison);
            if (list.size() > MAX_PER_CONVERSATION) {
                list.remove(0);
            }
        }
    }

    /** 在这个会话最近的比价里按名称找酒店，新的优先；内存里没有就查消息里存的比价卡片。 */
    public Optional<Found> find(String conversationId, String hotel) {
        if (conversationId == null || hotel == null) {
            return Optional.empty();
        }
        List<Comparison> list = byConversation.get(conversationId);
        if (list != null) {
            synchronized (list) {
                Optional<Found> found = match(list, hotel);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return match(fromMessages(conversationId), hotel);
    }

    private static Optional<Found> match(List<Comparison> list, String hotel) {
        for (int i = list.size() - 1; i >= 0; i--) {
            Comparison c = list.get(i);
            for (Entry e : c.hotels()) {
                if (AmapPlaceService.similarName(hotel, e.hotel())) {
                    return Optional.of(new Found(c, e));
                }
            }
        }
        return Optional.empty();
    }

    /** 会话消息里存的酒店比价卡片（metadata.cards 里 kind=hotels 的），按时间先后。 */
    private List<Comparison> fromMessages(String conversationId) {
        List<Comparison> out = new ArrayList<>();
        try {
            for (ChatMessageEntity m : messageMapper.selectRecent(conversationId, HISTORY_MESSAGES)) {
                if (m.getMetadata() == null) {
                    continue;
                }
                for (JsonNode card : objectMapper.readTree(m.getMetadata()).path("cards")) {
                    if (!"hotels".equals(card.path("kind").asText())) {
                        continue;
                    }
                    JsonNode d = card.path("data");
                    List<Entry> hotels = new ArrayList<>();
                    for (JsonNode r : d.path("results")) {
                        hotels.add(new Entry(r.path("hotel").asText(), List.of(objectMapper.treeToValue(r.path("quotes"),
                                HotelPriceSource.Quote[].class))));
                    }
                    out.add(new Comparison(d.path("city").asText(null), d.path("checkIn").asText(null),
                            d.path("checkOut").asText(null), d.path("queriedAt").asText(null), hotels));
                }
            }
        } catch (Exception e) {
            log.warn("从会话 {} 的消息里读酒店比价失败：{}", conversationId, e.getMessage());
        }
        return out;
    }
}
