package com.portfolio.deployops.config;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Private configuration is generated only in a temporary test directory. */
class EmbeddedYamlImportTest {
    @TempDir Path directory;

    @Test
    void privateClasspathYamlOverridesDefaultsAndPreservesLiteralSecrets() throws Exception {
        String password = UUID.randomUUID() + "-$&'";
        String key = UUID.randomUUID().toString();
        String previous = "previous=" + UUID.randomUUID();
        String sso = UUID.randomUUID().toString();
        assertThat(org.springframework.core.io.support.SpringFactoriesLoader.loadFactoryNames(
                org.springframework.boot.EnvironmentPostProcessor.class, getClass().getClassLoader()))
                .contains(PublicConfigurationDefaults.class.getName());
        Files.writeString(directory.resolve("application-install.yml"), """
            {"spring":{"datasource":{"password":"%s"}},
             "server":{"port":19090}, "app":{"crypto":{"secret-key":"%s", "previous-keys":"%s"}, "sso":{"shared-secret":"%s"}}}
            """.formatted(password, key, previous, sso));
        Path parentDirectory = Files.createDirectory(directory.resolve("parent"));
        Files.writeString(parentDirectory.resolve("application-install.yml"), "{\"server\":{\"port\":19092}}");
        try (var parent = new URLClassLoader(new java.net.URL[] {parentDirectory.toUri().toURL()}, getClass().getClassLoader());
             var loader = new URLClassLoader(new java.net.URL[] {directory.toUri().toURL()}, parent) {
                 @Override public java.net.URL getResource(String name) {
                     return "application-install.yml".equals(name) ? findResource(name) : super.getResource(name);
                 }
                 @Override public java.util.Enumeration<java.net.URL> getResources(String name) throws java.io.IOException {
                     if (!"application-install.yml".equals(name)) return super.getResources(name);
                     var own = findResource(name);
                     return java.util.Collections.enumeration(own == null ? java.util.List.of() : java.util.List.of(own));
                 }
             }) {
            new ApplicationContextRunner().withClassLoader(loader)
                .withInitializer(context -> new PublicConfigurationDefaults().postProcessEnvironment(
                        context.getEnvironment(), new org.springframework.boot.SpringApplication()))
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var environment = context.getEnvironment();
                    assertThat(password.equals(environment.getProperty("spring.datasource.password"))).isTrue();
                    assertThat(environment.getProperty("server.port")).isEqualTo("19090");
                    assertThat(key.equals(environment.getProperty("app.crypto.secret-key"))).isTrue();
                    assertThat(previous.equals(environment.getProperty("app.crypto.previous-keys"))).isTrue();
                    assertThat(sso.equals(environment.getProperty("app.sso.shared-secret"))).isTrue();
                });
        }
    }
}
