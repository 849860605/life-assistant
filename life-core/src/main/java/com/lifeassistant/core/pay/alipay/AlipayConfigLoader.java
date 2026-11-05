package com.lifeassistant.core.pay.alipay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 按 life.pay.alipay.mode 读出当前要用的支付宝应用配置；缺字段直接报错，不回退到示例值、占位值或别的网关。
 */
@Component
@RequiredArgsConstructor
public class AlipayConfigLoader {

    /** 沙箱网关，只在 sandbox 模式下使用。 */
    static final String SANDBOX_GATEWAY = "https://openapi-sandbox.dl.alipaydev.com/gateway.do";
    /** 快速沙箱配置文件名，固定放在项目根目录，已在 .gitignore 里排除。 */
    static final String SANDBOX_FILE = ".alipay-sandbox.json";

    private final AlipayProperties properties;
    private final ObjectMapper objectMapper;
    private final ApplicationContext applicationContext;

    /** 读出当前模式的配置；sandbox 读本机沙箱文件，production 读环境变量里的正式应用配置。 */
    public AlipayAppConfig load() {
        String mode = properties.getMode() == null ? "" : properties.getMode().trim();
        return switch (mode) {
            case "sandbox" -> loadSandbox();
            case "production" -> loadProduction();
            default -> throw notReady("ALIPAY_MODE 只能是 sandbox 或 production");
        };
    }

    /** 沙箱：从 .alipay-sandbox.json 的 appIds[0] 取 APPID、PKCS#8 应用私钥（Java 用 appPrivateKey）、支付宝公钥和 pid。 */
    private AlipayAppConfig loadSandbox() {
        Path file = locateSandboxFile();
        JsonNode root;
        try {
            root = objectMapper.readTree(file.toFile());
        } catch (IOException e) {
            throw notReady("沙箱配置文件读不出来：" + file);
        }
        JsonNode app = root.path("appIds").path(0);
        return new AlipayAppConfig(true, SANDBOX_GATEWAY,
                required(app, "appId"),
                required(app, "appPrivateKey"),
                required(app, "alipayPublicKey"),
                required(app, "pid"));
    }

    /** 正式：APPID、应用私钥、支付宝公钥、收款 PID 都必须在环境变量里配好。 */
    private AlipayAppConfig loadProduction() {
        return new AlipayAppConfig(false,
                requiredValue(properties.getGateway(), "ALIPAY_GATEWAY"),
                requiredValue(properties.getAppId(), "ALIPAY_APP_ID"),
                requiredValue(properties.getPrivateKey(), "ALIPAY_PRIVATE_KEY"),
                requiredValue(properties.getAlipayPublicKey(), "ALIPAY_PUBLIC_KEY"),
                requiredValue(properties.getSellerId(), "ALIPAY_SELLER_ID"));
    }

    /**
     * 定位沙箱配置文件：配了 ALIPAY_SANDBOX_CONFIG（绝对路径）就用它；否则从启动类（本地是 life-api/target/classes）
     * 和本类的 class 所在位置往上找，第一个含 .alipay-sandbox.json 的目录就是项目根目录。不看启动目录。
     */
    private Path locateSandboxFile() {
        if (StringUtils.hasText(properties.getSandboxConfig())) {
            Path path = Path.of(properties.getSandboxConfig().trim());
            if (!path.isAbsolute()) {
                throw notReady("ALIPAY_SANDBOX_CONFIG 要写绝对路径");
            }
            if (!Files.isRegularFile(path)) {
                throw notReady("沙箱配置文件不存在：" + path);
            }
            return path;
        }
        for (Path start : codeLocations()) {
            for (Path dir = start; dir != null; dir = dir.getParent()) {
                Path candidate = dir.resolve(SANDBOX_FILE);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        }
        throw notReady("没找到项目根目录的 " + SANDBOX_FILE + "，打包运行时请用 ALIPAY_SANDBOX_CONFIG 指定绝对路径");
    }

    /** 往上找的起点：启动类所在位置在前，本类所在位置在后；打包成 jar 时取不到文件路径就跳过。 */
    private List<Path> codeLocations() {
        Set<Class<?>> anchors = new LinkedHashSet<>();
        applicationContext.getBeansWithAnnotation(SpringBootApplication.class).values()
                .forEach(bean -> anchors.add(ClassUtils.getUserClass(bean)));
        anchors.add(AlipayConfigLoader.class);
        List<Path> locations = new ArrayList<>();
        for (Class<?> anchor : anchors) {
            try {
                CodeSource source = anchor.getProtectionDomain().getCodeSource();
                locations.add(Path.of(source.getLocation().toURI()).toAbsolutePath());
            } catch (Exception e) {
                // jar 内的类没有普通文件路径，换下一个起点
            }
        }
        return locations;
    }

    /** 取 JSON 里的必填字段，原样返回，不截断、不加 PEM 头尾。 */
    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank()) {
            throw notReady("沙箱配置缺少 " + field);
        }
        return value;
    }

    /** 取环境变量里的必填值，只去掉首尾空白。 */
    private static String requiredValue(String value, String envName) {
        if (!StringUtils.hasText(value)) {
            throw notReady("没有配置 " + envName);
        }
        return value.trim();
    }

    /** 配置不可用：报服务内部错误，提示缺什么，不带任何密钥内容。 */
    private static BizException notReady(String reason) {
        return new BizException(ErrorCode.INTERNAL, "支付宝收款还没配置好：" + reason);
    }
}
