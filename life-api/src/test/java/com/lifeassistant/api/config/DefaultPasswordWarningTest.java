package com.lifeassistant.api.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultPasswordWarningTest {

    private static final String[] NONE = new String[0];

    @Test
    void warnsOnlyForDefaultPasswordOutsideLocalDev() {
        assertThat(DefaultPasswordWarning.shouldWarn("life", "jdbc:postgresql://postgres:5432/life?x=1", NONE)).isTrue();
        assertThat(DefaultPasswordWarning.shouldWarn("life", "jdbc:postgresql://localhost:5432/life", NONE)).isFalse();
        assertThat(DefaultPasswordWarning.shouldWarn("life", "jdbc:postgresql://localhost:5432/life", new String[]{"prod"})).isTrue();
        assertThat(DefaultPasswordWarning.shouldWarn("s3cret", "jdbc:postgresql://postgres:5432/life", NONE)).isFalse();
    }

    @Test
    void readsHostFromJdbcUrl() {
        assertThat(DefaultPasswordWarning.host("jdbc:postgresql://db.internal:5432/life?stringtype=unspecified")).isEqualTo("db.internal");
        assertThat(DefaultPasswordWarning.host("jdbc:postgresql://127.0.0.1/life")).isEqualTo("127.0.0.1");
        assertThat(DefaultPasswordWarning.host("jdbc:postgresql://[::1]:5432/life")).isEqualTo("[::1]");
        assertThat(DefaultPasswordWarning.host("garbage")).isEmpty();
    }
}
