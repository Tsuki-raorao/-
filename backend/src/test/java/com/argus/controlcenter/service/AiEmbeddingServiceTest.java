package com.argus.controlcenter.service;

import com.argus.controlcenter.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiEmbeddingServiceTest {
    @Test
    void defaultsToDeterministicLocalProviderWithoutNetworkConfiguration() {
        AiProperties properties = new AiProperties();
        AiEmbeddingService service = new AiEmbeddingService(properties, new ObjectMapper());

        AiEmbeddingService.Embedding first = service.embed("节点心跳状态");
        AiEmbeddingService.Embedding second = service.embed("节点心跳状态");

        assertThat(first.model()).isEqualTo(AiEmbeddingService.LOCAL_MODEL);
        assertThat(first.vector()).hasSize(64).containsExactly(second.vector());
    }

    @Test
    void malformedRemoteConfigurationFallsBackLocallyWithoutCallingNetwork() {
        AiProperties properties = new AiProperties();
        properties.setEmbeddingModel("test-remote");
        properties.setEmbeddingBaseUrl("http://[");

        AiEmbeddingService.Embedding result = new AiEmbeddingService(properties, new ObjectMapper()).embed("fallback");

        assertThat(result.model()).isEqualTo(AiEmbeddingService.LOCAL_MODEL);
        assertThat(result.vector()).hasSize(64);
    }
}
