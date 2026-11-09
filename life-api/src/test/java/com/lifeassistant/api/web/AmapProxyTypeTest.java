package com.lifeassistant.api.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AmapProxyTypeTest {

    @Test
    void passesScriptJsonImageTextAndBlocksTheRest() {
        assertThat(AmapJsController.safeType("application/json;charset=UTF-8")).isEqualTo("application/json;charset=UTF-8");
        assertThat(AmapJsController.safeType("application/javascript")).isEqualTo("application/javascript");
        assertThat(AmapJsController.safeType("text/javascript; charset=utf-8")).isEqualTo("text/javascript; charset=utf-8");
        assertThat(AmapJsController.safeType("image/png")).isEqualTo("image/png");
        assertThat(AmapJsController.safeType("text/plain")).isEqualTo("text/plain");
        assertThat(AmapJsController.safeType("text/html; charset=utf-8")).isEqualTo("application/octet-stream");
        assertThat(AmapJsController.safeType("image/svg+xml")).isEqualTo("application/octet-stream");
        assertThat(AmapJsController.safeType("application/xhtml+xml")).isEqualTo("application/octet-stream");
        assertThat(AmapJsController.safeType("not a type")).isEqualTo("application/octet-stream");
    }
}
