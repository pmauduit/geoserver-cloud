/*
 * (c) 2020 Open Source Geospatial Foundation - all rights reserved This code is licensed under the
 * GPL 2.0 license, available at the root application directory.
 */
package org.geoserver.cloud.ogcapi.app;

import org.geoserver.platform.GeoServerResourceLoader;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.File;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "testdatadir"})
class OgcApiApplicationDataDirectoryTest extends OgcApiApplicationTest {

    private static @TempDir File tmpDataDir;

    @Configuration
    static class ContextConfiguration {
        // TODO why are these beans needed ?
        @Bean
        public GeoServerResourceLoader geoserverResourceLoader() {
            return new GeoServerResourceLoader(tmpDataDir);
        }

        @Bean
        public ServletWebServerFactory servletWebServerFactory() {
            return new TomcatServletWebServerFactory();
        }
    }

    @DynamicPropertySource
    static void registerPgProperties(@NotNull DynamicPropertyRegistry registry) {
        String datadir = tmpDataDir.getAbsolutePath();
        registry.add("data_directory", () -> datadir);
        registry.add("gwc.wms-integration", () -> "true");
    }

    @Test
    void testLoadApplicationContextSuccessfully() {
        // everything went fine ? good
    }
}
