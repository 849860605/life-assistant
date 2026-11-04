package com.lifeassistant.core.integration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import com.lifeassistant.core.config.LifeProperties;
import com.lifeassistant.core.crypto.FieldCipher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertyResolver;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 第三方平台的"当前生效配置"：后台填了的字段用后台的，没填的用 .env（application.yml）里的；
 * 平台被停用时 {@link #value} 返回空，各客户端按"没配 Key"处理，复用原来的降级逻辑。
 * 配置读一次缓存在内存里，后台修改后刷新；各客户端每次调用时来取，所以改完立即生效。
 */
@Slf4j
@Service
public class IntegrationSettings {

    /** 读库失败时多久后再试，期间只用 .env 的配置 */
    private static final long RETRY_MILLIS = 30_000;
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final FieldCipher cipher;
    private final LifeProperties properties;
    private final PropertyResolver env;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile Map<String, Stored> cache;
    private volatile long failedAt;

    /** 数据库里存的一行：开关、后台填的非敏感字段和敏感字段（已解密）、最近修改。 */
    public record Stored(boolean enabled, Map<String, String> config, Map<String, String> secrets,
                         OffsetDateTime updatedAt, Long updatedBy) {
    }

    @Autowired
    public IntegrationSettings(JdbcTemplate jdbc, FieldCipher cipher, LifeProperties properties, Environment env) {
        this(jdbc, cipher, properties, (PropertyResolver) env);
    }

    private IntegrationSettings(JdbcTemplate jdbc, FieldCipher cipher, LifeProperties properties, PropertyResolver env) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.properties = properties;
        this.env = env;
    }

    /** 不连数据库、只用 LifeProperties 的实例，给单元测试用。 */
    public static IntegrationSettings of(LifeProperties properties) {
        return new IntegrationSettings(null, null, properties,
                new PropertySourcesPropertyResolver(new MutablePropertySources()));
    }

    /** 平台是否启用（后台的开关；还没建过记录的按默认开关）。 */
    public boolean enabled(String code) {
        Stored stored = stored().get(code);
        if (stored != null) {
            return stored.enabled();
        }
        return IntegrationCatalog.find(code).map(p -> p.defaultEnabled().apply(properties, env)).orElse(false);
    }

    /** 字段的生效值；平台停用时返回 null。 */
    public String value(String code, String field) {
        return enabled(code) ? rawValue(code, field) : null;
    }

    /** 字段的生效值，不管开关：后台填了用后台的，否则用 .env 的；都没有返回 null。 */
    public String rawValue(String code, String field) {
        String saved = savedValue(code, field);
        return saved != null ? saved : fallback(code, field);
    }

    /** 后台填的值（没填返回 null）。 */
    public String savedValue(String code, String field) {
        Stored stored = stored().get(code);
        if (stored == null) {
            return null;
        }
        String v = stored.secrets().get(field);
        if (!StringUtils.hasText(v)) {
            v = stored.config().get(field);
        }
        return StringUtils.hasText(v) ? v : null;
    }

    /** .env（application.yml）里的值，没有返回 null。 */
    public String fallback(String code, String field) {
        return IntegrationCatalog.find(code).flatMap(p -> p.field(field))
                .map(f -> f.fallback().apply(properties, env))
                .filter(StringUtils::hasText).orElse(null);
    }

    /** 数据库里这个平台的记录（没有返回 null），后台列表用。 */
    public Stored stored(String code) {
        return stored().get(code);
    }

    /**
     * 后台保存一个平台：values 里非空的字段覆盖后台的值（敏感字段留空表示不改），clear 里的字段删掉后台的值、
     * 改回用 .env 的；enabled 为 null 表示不改开关。保存后刷新缓存。
     */
    public void save(String code, Boolean enabled, Map<String, String> values, Collection<String> clear, long adminId) {
        IntegrationCatalog.Platform platform = IntegrationCatalog.find(code)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "没有这个平台：" + code));
        requireDb();
        Stored old = stored().get(code);
        Map<String, String> config = new LinkedHashMap<>(old == null ? Map.of() : old.config());
        Map<String, String> secrets = new LinkedHashMap<>(old == null ? Map.of() : old.secrets());
        boolean secretGiven = false;
        boolean addressChanged = false;
        for (Map.Entry<String, String> e : (values == null ? Map.<String, String>of() : values).entrySet()) {
            IntegrationCatalog.Field field = platform.field(e.getKey())
                    .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "没有这个字段：" + e.getKey()));
            String v = e.getValue() == null ? "" : e.getValue().trim();
            if (v.isEmpty() || field.kind() == IntegrationCatalog.Kind.READONLY) {
                continue;
            }
            if (v.length() > 500) {
                throw new BizException(ErrorCode.BAD_REQUEST, field.label() + "太长了");
            }
            if (field.kind() == IntegrationCatalog.Kind.SECRET) {
                secrets.put(field.name(), v);
                secretGiven = true;
            } else {
                if ((field.name().endsWith("url") || field.name().equals("server")) &&!v.matches("https?://\\S+")) {
                    throw new BizException(ErrorCode.BAD_REQUEST, field.label() + "要以 https:// 或 http:// 开头");
                }
                if (field.bindsSecret() && !v.equals(rawValue(code, field.name()))) {
                    addressChanged = true;
                }
                config.put(field.name(), v);
            }
        }
        for (String name : clear == null ? Set.<String>of() : clear) {
            config.remove(name);
            secrets.remove(name);
        }
        // Key 会发到这个地址，地址换了就得重新填 Key，免得已存的 Key 被发去别的地方
        if (addressChanged && !secretGiven && platform.fields().stream()
                .anyMatch(f -> f.kind() == IntegrationCatalog.Kind.SECRET && rawValue(code, f.name()) != null)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "改了地址要同时重新填 Key");
        }
        boolean on = enabled != null ? enabled : enabled(code);
        String secretJson = secrets.isEmpty() ? null : cipher.encrypt(json(secrets));
        jdbc.update("""
                INSERT INTO t_integration (code, enabled, config, secret_enc, updated_at, updated_by)
                VALUES (?, ?, CAST(? AS jsonb), ?, now(), ?)
                ON CONFLICT (code) DO UPDATE SET enabled = EXCLUDED.enabled, config = EXCLUDED.config,
                    secret_enc = EXCLUDED.secret_enc, updated_at = now(), updated_by = EXCLUDED.updated_by
                """, code, on, json(config), secretJson, adminId);
        reload();
    }

    /**
     * 启动完成后给每个平台补一条记录（开关按 .env 里的现状，字段不填、继续用 .env 的值），已有的不动；
     * 以后新接的平台也会自动补上。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void ensureRows() {
        if (jdbc == null) {
            return;
        }
        try {
            for (IntegrationCatalog.Platform p : IntegrationCatalog.PLATFORMS) {
                jdbc.update("INSERT INTO t_integration (code, enabled) VALUES (?, ?) ON CONFLICT (code) DO NOTHING",
                        p.code(), p.defaultEnabled().apply(properties, env));
            }
        } catch (DataAccessException e) {
            log.warn("初始化第三方平台配置失败：{}", e.getMessage());
        }
        reload();
    }

    /** 重新从数据库读一遍。 */
    public synchronized void reload() {
        cache = null;
        failedAt = 0;
        stored();
    }

    private Map<String, Stored> stored() {
        Map<String, Stored> current = cache;
        if (current != null) {
            return current;
        }
        if (jdbc == null || System.currentTimeMillis() - failedAt < RETRY_MILLIS) {
            return Map.of();
        }
        synchronized (this) {
            if (cache != null) {
                return cache;
            }
            try {
                Map<String, Stored> loaded = new HashMap<>();
                jdbc.query("SELECT code, enabled, config::text AS config, secret_enc, updated_at, updated_by FROM t_integration",
                        rs -> {
                            loaded.put(rs.getString("code"), new Stored(rs.getBoolean("enabled"),
                                    parse(rs.getString("config")), secrets(rs.getString("code"), rs.getString("secret_enc")),
                                    rs.getObject("updated_at", OffsetDateTime.class),
                                    (Long) rs.getObject("updated_by")));
                        });
                cache = Map.copyOf(loaded);
                return cache;
            } catch (DataAccessException e) {
                log.warn("读第三方平台配置失败，暂时用 .env 的配置：{}", e.getMessage());
                failedAt = System.currentTimeMillis();
                return Map.of();
            }
        }
    }

    /** 解密敏感字段；密钥变了解不开时当作没填，回退 .env，并打日志。 */
    private Map<String, String> secrets(String code, String encrypted) {
        if (!StringUtils.hasText(encrypted)) {
            return Map.of();
        }
        try {
            return parse(cipher.decrypt(encrypted));
        } catch (IllegalStateException e) {
            log.warn("平台 {} 的 Key 解密失败（LIFE_CRYPTO_KEY 变了？），先用 .env 的：{}", code, e.getMessage());
            return Map.of();
        }
    }

    private Map<String, String> parse(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, STRING_MAP);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String json(Map<String, String> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void requireDb() {
        if (jdbc == null) {
            throw new IllegalStateException("没有数据库，不能保存平台配置");
        }
    }
}
