package com.portfolio.deployops.config;

import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/** Supports an operator-supplied embedded configuration without shipping a configuration file. */
public final class PublicConfigurationDefaults implements EnvironmentPostProcessor, Ordered {
    @Override public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment.getPropertySources().addLast(new MapPropertySource("publicSourceDefaults",
                Map.of("spring.config.import", "optional:classpath:application-install.yml")));
    }

    @Override public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER - 1;
    }
}
