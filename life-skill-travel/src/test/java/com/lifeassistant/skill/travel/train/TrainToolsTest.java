package com.lifeassistant.skill.travel.train;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 飞猪火车票结果的解析和按偏好挑选。 */
class TrainToolsTest {

    private static final String RESULT = """
            {"data":{"itemList":[
              {"journeys":[{"journeyType":"直达","segments":[{"marketingTransportNo":"G6001","depDateTime":"2026-11-20 07:00:00",
                "arrDateTime":"2026-11-20 10:20:00","depStationName":"深圳北站","arrStationName":"长沙南站","seatClassName":"二等座","quantity":12}]}],
               "price":"394.50","totalDuration":"200","jumpUrl":"https://fliggy/1"},
              {"journeys":[{"journeyType":"中转","segments":[
                {"marketingTransportNo":"G1","depDateTime":"2026-11-20 06:11:00","arrDateTime":"2026-11-20 06:48:00","depStationName":"深圳北站","arrStationName":"广州南站","seatClassName":"二等座"},
                {"marketingTransportNo":"G2","depDateTime":"2026-11-20 07:17:00","arrDateTime":"2026-11-20 09:42:00","depStationName":"广州南站","arrStationName":"长沙南站","seatClassName":"二等座"}]}],
               "price":"388.50","totalDuration":"211","jumpUrl":"https://fliggy/2"},
              {"journeys":[{"journeyType":"直达","segments":[{"marketingTransportNo":"G6011","depDateTime":"2026-11-20 14:00:00",
                "arrDateTime":"2026-11-20 17:05:00","depStationName":"深圳北站","arrStationName":"长沙南站","seatClassName":"二等座","quantity":0}]}],
               "price":"394.50","totalDuration":"185","jumpUrl":"https://fliggy/3"}
            ]},"status":0}""";

    @Test
    void parsesDirectAndTransferTrains() throws Exception {
        JsonNode json = new ObjectMapper().readTree(RESULT);
        List<TrainTools.Train> trains = TrainTools.parse(json);
        assertThat(trains).hasSize(3);
        TrainTools.Train first = trains.get(0);
        assertThat(first.trainNo()).isEqualTo("G6001");
        assertThat(first.depTime()).isEqualTo("07:00");
        assertThat(first.arrStation()).isEqualTo("长沙南站");
        assertThat(first.price()).isEqualTo(394.5);
        assertThat(first.left()).isEqualTo(12);
        TrainTools.Train transfer = trains.get(1);
        assertThat(transfer.transfer()).isTrue();
        assertThat(transfer.trainNo()).isEqualTo("G1/G2");
        assertThat(transfer.via()).isEqualTo("广州南站");
    }

    @Test
    void picksDirectWithTicketsAndHonorsPreference() throws Exception {
        List<TrainTools.Train> trains = TrainTools.parse(new ObjectMapper().readTree(RESULT));
        // 有直达时不要中转；有票的优先，没票的去掉
        assertThat(TrainTools.pick(trains, null)).extracting(TrainTools.Train::trainNo).containsExactly("G6001");
        // 下午出发：只有一班下午的，没票也只能给它
        assertThat(TrainTools.pick(trains, "下午")).extracting(TrainTools.Train::trainNo).containsExactly("G6011");
    }

    @Test
    void trainNumberInName() {
        assertThat(TrainTools.TRAIN_IN_NAME.matcher("高铁 G6001 深圳北 → 长沙南").find()).isTrue();
        assertThat(TrainTools.TRAIN_IN_NAME.matcher("南航 CZ3401 宝安T3 → 双流T2").find()).isFalse();
    }
}
