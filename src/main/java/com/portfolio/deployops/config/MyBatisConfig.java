package com.portfolio.deployops.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.portfolio.deployops.repository")
public class MyBatisConfig {
    @org.springframework.context.annotation.Bean
    org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer domainMapping() {
        return configuration -> {
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setJdbcTypeForNull(org.apache.ibatis.type.JdbcType.NULL);
        };
    }
}
