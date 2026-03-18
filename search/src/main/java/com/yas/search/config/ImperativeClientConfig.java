package com.yas.search.config;

import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchConnectionDetails;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.client.ClientConfiguration;
import org.springframework.data.elasticsearch.client.ClientConfiguration.MaybeSecureClientConfigurationBuilder;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchConfiguration;
import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories;

@Configuration
@EnableElasticsearchRepositories(basePackages = "com.yas.search.repository")
@ComponentScan(basePackages = "com.yas.search.service")
@RequiredArgsConstructor
public class ImperativeClientConfig extends ElasticsearchConfiguration {

    private static final String HTTP_PREFIX = "http://";
    private static final String HTTPS_PREFIX = "https://";
    private static final int DEFAULT_ELASTICSEARCH_PORT = 9200;
    private static final String DEFAULT_ELASTICSEARCH_ENDPOINT = "localhost:9200";

    private final ElasticsearchDataConfig elasticsearchConfig;
    private final ObjectProvider<ElasticsearchConnectionDetails> elasticsearchConnectionDetailsProvider;

    @Value("${spring.elasticsearch.uris:}")
    private String elasticsearchUris;

    @Override
    public ClientConfiguration clientConfiguration() {
        EndpointConfig endpointConfig = resolveEndpointConfig();
        MaybeSecureClientConfigurationBuilder builder = ClientConfiguration.builder()
            .connectedTo(endpointConfig.endpoints().toArray(String[]::new));

        if (endpointConfig.useSsl()) {
            builder = (MaybeSecureClientConfigurationBuilder) builder.usingSsl();
        }

        String username = endpointConfig.username();
        String password = endpointConfig.password();
        if (username != null && !username.isBlank() && password != null) {
            return builder
                .withBasicAuth(username, password)
                .build();
        }

        return builder.build();
    }

    private EndpointConfig resolveEndpointConfig() {
        EndpointConfig fromConnectionDetails = resolveFromConnectionDetails();
        if (fromConnectionDetails != null) {
            return fromConnectionDetails;
        }

        EndpointConfig fromUris = resolveFromElasticsearchUris();
        if (fromUris != null) {
            return fromUris;
        }

        return resolveFromConfiguredUrl();
    }

    private EndpointConfig resolveFromConnectionDetails() {
        ElasticsearchConnectionDetails connectionDetails = elasticsearchConnectionDetailsProvider.getIfAvailable();
        if (connectionDetails == null || connectionDetails.getNodes() == null || connectionDetails.getNodes().isEmpty()) {
            return null;
        }

        List<ElasticsearchConnectionDetails.Node> nodes = connectionDetails.getNodes();
        List<String> endpoints = nodes.stream()
                .map(node -> node.hostname() + ":" + node.port())
                .toList();
        boolean useSsl = nodes.stream()
                .anyMatch(node -> node.protocol() == ElasticsearchConnectionDetails.Node.Protocol.HTTPS);
        return new EndpointConfig(endpoints, useSsl, connectionDetails.getUsername(), connectionDetails.getPassword());
    }

    private EndpointConfig resolveFromElasticsearchUris() {
        if (elasticsearchUris == null || elasticsearchUris.isBlank()) {
            return null;
        }

        String firstUri = elasticsearchUris.split(",")[0].trim();
        if (isHttpBasedUri(firstUri)) {
            URI uri = URI.create(firstUri);
            int port = uri.getPort() > 0 ? uri.getPort() : DEFAULT_ELASTICSEARCH_PORT;
            boolean useSsl = firstUri.startsWith(HTTPS_PREFIX);
            return createConfig(List.of(uri.getHost() + ":" + port), useSsl);
        }

        return createConfig(List.of(firstUri), false);
    }

    private EndpointConfig resolveFromConfiguredUrl() {
        String configuredUrl = elasticsearchConfig.getUrl();
        if (configuredUrl == null || configuredUrl.isBlank()) {
            return createConfig(List.of(DEFAULT_ELASTICSEARCH_ENDPOINT), false);
        }

        if (isHttpBasedUri(configuredUrl)) {
            URI uri = URI.create(configuredUrl);
            int port = uri.getPort() > 0 ? uri.getPort() : DEFAULT_ELASTICSEARCH_PORT;
            boolean useSsl = configuredUrl.startsWith(HTTPS_PREFIX);
            return createConfig(List.of(uri.getHost() + ":" + port), useSsl);
        }

        if (!configuredUrl.contains(":")) {
            configuredUrl = configuredUrl + ":" + DEFAULT_ELASTICSEARCH_PORT;
        }

        return createConfig(List.of(configuredUrl), false);
    }

    private boolean isHttpBasedUri(String url) {
        return url.startsWith(HTTP_PREFIX) || url.startsWith(HTTPS_PREFIX);
    }

    private EndpointConfig createConfig(List<String> endpoints, boolean useSsl) {
        return new EndpointConfig(endpoints, useSsl, elasticsearchConfig.getUsername(), elasticsearchConfig.getPassword());
    }

    private record EndpointConfig(List<String> endpoints, boolean useSsl, String username, String password) {
    }
}
