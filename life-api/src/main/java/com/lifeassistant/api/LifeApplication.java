package com.lifeassistant.api;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 启动类：扫描全部 com.lifeassistant 模块的 Bean，并只把标了 @Mapper 的接口注册为 MyBatis Mapper。
 */
@SpringBootApplication(scanBasePackages = "com.lifeassistant")
@MapperScan(basePackages = "com.lifeassistant", annotationClass = org.apache.ibatis.annotations.Mapper.class)
public class LifeApplication {

    /**
     * 启动应用。HTTPS 握手只用常见的密钥交换算法：新版 JDK 默认还带后量子混合算法，
     * 握手包变大，有的服务（如小红书点点）会直接断开握手。启动参数里指定过就不改。
     */
    public static void main(String[] args) {
        if (System.getProperty("jdk.tls.namedGroups") == null) {
            System.setProperty("jdk.tls.namedGroups", "x25519,secp256r1,secp384r1");
        }
        SpringApplication.run(LifeApplication.class, args);
    }
}
