package com.lifeassistant.skill.travel.flight;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 飞常准返回的 Python dict 文本能转成 JSON。 */
class PyLiteralTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void convertsQuotesAndKeywords() throws Exception {
        String py = "{'code': 200, 'data': [{'FlightNo': 'CZ3101', 'BoardGate': None, 'ok': True, 'x': False, "
                + "'note': \"it's \\\"fine\\\"\", 'Nonexistent': 'None'}]}";
        JsonNode n = mapper.readTree(PyLiteral.toJson(py));
        JsonNode f = n.get("data").get(0);
        assertThat(n.get("code").asInt()).isEqualTo(200);
        assertThat(f.get("FlightNo").asText()).isEqualTo("CZ3101");
        assertThat(f.get("BoardGate").isNull()).isTrue();
        assertThat(f.get("ok").asBoolean()).isTrue();
        assertThat(f.get("x").asBoolean()).isFalse();
        assertThat(f.get("note").asText()).isEqualTo("it's \"fine\"");
        assertThat(f.get("Nonexistent").asText()).isEqualTo("None");
    }

    @Test
    void escapedSingleQuoteInsideString() throws Exception {
        JsonNode n = mapper.readTree(PyLiteral.toJson("{'a': 'Xi\\'an'}"));
        assertThat(n.get("a").asText()).isEqualTo("Xi'an");
    }
}
