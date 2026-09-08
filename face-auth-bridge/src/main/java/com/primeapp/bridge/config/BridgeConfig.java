package com.primeapp.bridge.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(BridgeProperties.class)
public class BridgeConfig {

    /** Used only for multipart POST to deepface-worker-rs; timeouts tolerate cold models / slow CPU inference. */
    @Bean(name = "deepFaceWorkerRestTemplate")
    RestTemplate deepFaceWorkerRestTemplate() {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout((int) Duration.ofSeconds(30).toMillis());
        rf.setReadTimeout((int) Duration.ofMinutes(3).toMillis());
        return new RestTemplate(rf);
    }
}
