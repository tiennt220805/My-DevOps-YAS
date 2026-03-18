package com.yas.search.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchConnectionDetails;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.elasticsearch.client.ClientConfiguration;
import org.springframework.data.elasticsearch.support.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;

class ImperativeClientConfigTest {

    @Test
    void testClientConfiguration_whenConnectionDetailsAvailable_shouldUseConnectionDetailsWithSslAndBasicAuth() {
        ElasticsearchDataConfig dataConfig = new ElasticsearchDataConfig();
        dataConfig.setUrl("localhost:9200");

        ElasticsearchConnectionDetails connectionDetails = new ElasticsearchConnectionDetails() {
            @Override
            public List<Node> getNodes() {
                return List.of(new Node("tc-elasticsearch", 19321, Node.Protocol.HTTPS));
            }

            @Override
            public String getUsername() {
                return "tc-user";
            }

            @Override
            public String getPassword() {
                return "tc-pass";
            }
        };

        ObjectProvider<ElasticsearchConnectionDetails> provider =
            new StaticListableBeanFactory(Map.of("elasticsearchConnectionDetails", connectionDetails))
                .getBeanProvider(ElasticsearchConnectionDetails.class);

        ImperativeClientConfig config = new ImperativeClientConfig(dataConfig, provider);

        ClientConfiguration clientConfiguration = config.clientConfiguration();

        assertEquals(List.of(new InetSocketAddress("tc-elasticsearch", 19321)), clientConfiguration.getEndpoints());
        assertTrue(clientConfiguration.useSsl());
        assertEquals(
            "Basic " + HttpHeaders.encodeBasicAuth("tc-user", "tc-pass"),
            clientConfiguration.getDefaultHeaders().getFirst(HttpHeaders.AUTHORIZATION));
    }

    @Test
    void testClientConfiguration_whenNoConnectionDetailsAndUriIsHttps_shouldParseHostPortAndEnableSsl() {
        ElasticsearchDataConfig dataConfig = new ElasticsearchDataConfig();
        dataConfig.setUsername("cfg-user");
        dataConfig.setPassword("cfg-pass");

        ObjectProvider<ElasticsearchConnectionDetails> provider =
            new StaticListableBeanFactory().getBeanProvider(ElasticsearchConnectionDetails.class);

        ImperativeClientConfig config = new ImperativeClientConfig(dataConfig, provider);
        ReflectionTestUtils.setField(config, "elasticsearchUris", "https://elastic-ci:9443");

        ClientConfiguration clientConfiguration = config.clientConfiguration();

        assertEquals(List.of(new InetSocketAddress("elastic-ci", 9443)), clientConfiguration.getEndpoints());
        assertTrue(clientConfiguration.useSsl());
        assertEquals(
            "Basic " + HttpHeaders.encodeBasicAuth("cfg-user", "cfg-pass"),
            clientConfiguration.getDefaultHeaders().getFirst(HttpHeaders.AUTHORIZATION));
    }

    @Test
    void testClientConfiguration_whenConfiguredUrlHasNoPort_shouldAppendDefaultPort() {
        ElasticsearchDataConfig dataConfig = new ElasticsearchDataConfig();
        dataConfig.setUrl("elasticsearch");

        ObjectProvider<ElasticsearchConnectionDetails> provider =
            new StaticListableBeanFactory().getBeanProvider(ElasticsearchConnectionDetails.class);

        ImperativeClientConfig config = new ImperativeClientConfig(dataConfig, provider);
        ReflectionTestUtils.setField(config, "elasticsearchUris", "");

        ClientConfiguration clientConfiguration = config.clientConfiguration();

        assertEquals(List.of(new InetSocketAddress("elasticsearch", 9200)), clientConfiguration.getEndpoints());
        assertFalse(clientConfiguration.useSsl());
    }

    @Test
    void testClientConfiguration_whenUsernameBlank_shouldNotSetBasicAuthHeader() {
        ElasticsearchDataConfig dataConfig = new ElasticsearchDataConfig();
        dataConfig.setUrl("elasticsearch:9200");
        dataConfig.setUsername(" ");
        dataConfig.setPassword("secret");

        ObjectProvider<ElasticsearchConnectionDetails> provider =
            new StaticListableBeanFactory().getBeanProvider(ElasticsearchConnectionDetails.class);

        ImperativeClientConfig config = new ImperativeClientConfig(dataConfig, provider);
        ReflectionTestUtils.setField(config, "elasticsearchUris", "");

        ClientConfiguration clientConfiguration = config.clientConfiguration();

        assertEquals(List.of(new InetSocketAddress("elasticsearch", 9200)), clientConfiguration.getEndpoints());
        assertNull(clientConfiguration.getHeadersSupplier().get().getFirst(HttpHeaders.AUTHORIZATION));
    }
}
