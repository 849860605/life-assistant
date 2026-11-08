package com.lifeassistant.core.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 模型给的坏参数：空值补成 null；合法 JSON（包括字符串里有 ": ,"）原样不动。 */
class RepairJsonTest {

    @Test
    void fillsMissingValues() {
        assertThat(ParallelToolCallingManager.repairJson("{\"a\": 1, \"b\": , \"c\": }"))
                .isEqualTo("{\"a\": 1, \"b\": null, \"c\": null}");
    }

    @Test
    void keepsValidJson() {
        String ok = "{\"note\": \"时间: , 待定\", \"n\": 2}";
        assertThat(ParallelToolCallingManager.repairJson(ok)).isEqualTo(ok);
    }
}
