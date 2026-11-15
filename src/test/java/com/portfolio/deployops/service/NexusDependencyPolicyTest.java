package com.portfolio.deployops.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NexusDependencyPolicyTest {
    @Test void mavenCoordinatesAreNeverSilentlyRewritten() {
        assertEquals("org/example/a/1/a-1.jar", NexusDependencyPolicy.target("maven2", "org/example", "org/example/a/1/a-1.jar"));
        assertThrows(IllegalArgumentException.class, () -> NexusDependencyPolicy.target("maven2", "org/company", "org/other/a/1/a-1.jar"));
        assertEquals("team/manual/readme.pdf", NexusDependencyPolicy.target("raw", "team", "manual/readme.pdf"));
    }

    @Test void traversalAndProtocolPathsAreRejected() {
        for (String path : new String[]{"../a", "a/../b", "/tmp/a", "a//b", "a\\b", "a/%2e%2e/b", "http://x/a", "a/"})
            assertThrows(IllegalArgumentException.class, () -> NexusDependencyPolicy.path(path), path);
        assertThrows(IllegalArgumentException.class, () -> NexusDependencyPolicy.format("npm"));
        assertThrows(IllegalArgumentException.class, () -> NexusDependencyPolicy.repository("../repo"));
    }

    @Test void totalHasNoFixedProductLimitButChunksAndOverflowAreChecked() {
        assertDoesNotThrow(() -> NexusDependencyPolicy.chunk(Long.MAX_VALUE, Long.MAX_VALUE - 1, Long.MAX_VALUE - 1, 1, 8_388_608));
        assertThrows(IllegalArgumentException.class, () -> NexusDependencyPolicy.chunk(Long.MAX_VALUE, Long.MAX_VALUE - 1, Long.MAX_VALUE - 1, 2, 8_388_608));
        assertThrows(IllegalArgumentException.class, () -> NexusDependencyPolicy.chunk(20_000_000, 0, 0, 8_388_609, 8_388_608));
        assertThrows(IllegalArgumentException.class, () -> NexusDependencyPolicy.chunk(100, 0, 20, 10, 8_388_608));
    }
}
