package com.tuapp.eventfoto.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.time.Duration;

@Configuration
public class S3Config {

    // Fase 8 - Test 9 (degradación de infraestructura): sin timeouts explícitos, un R2
    // inalcanzable dejaba la request colgada ~9s (default del Apache HttpClient interno
    // del SDK) antes de fallar, reteniendo todo ese tiempo una conexión de HikariCP en
    // confirmUpload (que es @Transactional). Con estos timeouts, una falla de R2 se
    // resuelve rápido y libera la conexión antes.
    private static final Duration API_CALL_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration API_CALL_ATTEMPT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(3);

    @Value("${cloudflare.r2.access-key}")
    private String accessKey;

    @Value("${cloudflare.r2.secret-key}")
    private String secretKey;

    @Value("${cloudflare.r2.endpoint}")
    private String endpoint;

    @Bean
    public S3Client s3Client() {
        AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKey, secretKey);

        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .region(Region.of("auto"))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .httpClientBuilder(ApacheHttpClient.builder()
                        .connectionTimeout(CONNECTION_TIMEOUT))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(API_CALL_TIMEOUT)
                        .apiCallAttemptTimeout(API_CALL_ATTEMPT_TIMEOUT)
                        .build())
                .build();
    }

    @Bean
    public S3Presigner s3Presigner() {
        AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKey, secretKey);

        return S3Presigner.builder()
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .region(Region.of("auto"))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }
}
