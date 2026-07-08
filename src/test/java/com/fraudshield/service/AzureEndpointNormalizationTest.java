package com.fraudshield.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AzureEndpointNormalizationTest {

    @Test
    void barePortalEndpoint_getsOpenAiV1Appended() {
        // Portal "Keys and Endpoint"页复制出来的裸域名 / bare domain pasted from the Portal
        assertThat(AzureOpenAIService.normalizeEndpoint("https://myres.openai.azure.com/"))
                .isEqualTo("https://myres.openai.azure.com/openai/v1");
        assertThat(AzureOpenAIService.normalizeEndpoint("https://myres.services.ai.azure.com"))
                .isEqualTo("https://myres.services.ai.azure.com/openai/v1");
        assertThat(AzureOpenAIService.normalizeEndpoint("https://myres.cognitiveservices.azure.com/"))
                .isEqualTo("https://myres.cognitiveservices.azure.com/openai/v1");
    }

    @Test
    void alreadyCorrectEndpoint_isLeftAlone() {
        assertThat(AzureOpenAIService.normalizeEndpoint(
                "https://myres.services.ai.azure.com/openai/v1"))
                .isEqualTo("https://myres.services.ai.azure.com/openai/v1");
    }

    @Test
    void trailingSlashesAndWhitespace_areStripped() {
        assertThat(AzureOpenAIService.normalizeEndpoint(
                "  https://myres.services.ai.azure.com/openai/v1//  "))
                .isEqualTo("https://myres.services.ai.azure.com/openai/v1");
    }
}
