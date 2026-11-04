package com.lifeassistant.core.setting;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/** 全站开关这类简单设置（t_app_setting，key-value），比如是否开放注册。 */
@Service
@RequiredArgsConstructor
public class AppSettingService {

    /** 是否开放邮箱注册，"true" 或 "false" */
    public static final String REGISTER_OPEN = "register.open";

    private final JdbcTemplate jdbc;

    /** 读一项设置，没有时返回默认值。 */
    public String get(String key, String fallback) {
        List<String> values = jdbc.queryForList("SELECT value FROM t_app_setting WHERE setting_key = ?", String.class, key);
        return values.isEmpty() || values.get(0) == null ? fallback : values.get(0);
    }

    /** 写一项设置，没有就新增。 */
    public void set(String key, String value) {
        jdbc.update("""
                INSERT INTO t_app_setting (setting_key, value, updated_at) VALUES (?, ?, now())
                ON CONFLICT (setting_key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()""", key, value);
    }

    /** 是否开放注册，默认开放。 */
    public boolean registerOpen() {
        return Boolean.parseBoolean(get(REGISTER_OPEN, "true"));
    }

    /** 打开或关闭注册。 */
    public void setRegisterOpen(boolean open) {
        set(REGISTER_OPEN, String.valueOf(open));
    }
}
