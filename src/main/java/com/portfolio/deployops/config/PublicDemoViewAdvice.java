package com.portfolio.deployops.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
@ConditionalOnProperty(name = "app.public-demo.enabled", havingValue = "true")
public class PublicDemoViewAdvice {
    @Value("${app.public-demo.username:${PORTFOLIO_DEMO_USERNAME:}}") private String demoLoginId;
    @Value("${app.public-demo.password:${PORTFOLIO_DEMO_PASSWORD:}}") private String demoPassword;
    @ModelAttribute("publicDemo") public boolean publicDemo() { return true; }
    @ModelAttribute("publicDemoLoginId") public String loginId() { return demoLoginId; }
    @ModelAttribute("publicDemoPassword") public String password() { return demoPassword; }
    @ModelAttribute("publicDemoMessage") public String message() { return PublicDemoAccessFilter.MESSAGE; }
}
