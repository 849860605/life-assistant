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

    /** 启动应用。 */
    public static void main(String[] args) {
        SpringApplication.run(LifeApplication.class, args);
    }
}
