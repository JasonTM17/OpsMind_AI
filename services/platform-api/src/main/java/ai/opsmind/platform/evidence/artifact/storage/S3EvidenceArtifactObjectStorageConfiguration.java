package ai.opsmind.platform.evidence.artifact.storage;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/** Builds the real client only when object storage is explicitly enabled. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "opsmind.evidence.artifact.storage",
    name = "enabled",
    havingValue = "true"
)
class S3EvidenceArtifactObjectStorageConfiguration {

    @Bean(name = "evidenceArtifactS3Client", destroyMethod = "close")
    S3Client evidenceArtifactS3Client(EvidenceArtifactStorageProperties properties) {
        properties.validateForEnablement();
        return S3Client.builder()
            .credentialsProvider(DefaultCredentialsProvider.create())
            .endpointOverride(properties.endpoint())
            .region(Region.of(properties.region()))
            .forcePathStyle(properties.pathStyleAccess())
            .httpClientBuilder(Apache5HttpClient.builder()
                .connectionTimeout(properties.connectTimeout())
                .connectionAcquisitionTimeout(properties.connectTimeout())
                .socketTimeout(properties.socketTimeout())
                .maxConnections(properties.maximumConnections()))
            .overrideConfiguration(ClientOverrideConfiguration.builder()
                .apiCallAttemptTimeout(properties.apiCallAttemptTimeout())
                .apiCallTimeout(properties.apiCallTimeout())
                .retryStrategy(AwsRetryStrategy.doNotRetry())
                .build())
            .build();
    }

    @Bean
    EvidenceArtifactObjectStorage evidenceArtifactObjectStorage(
        @Qualifier("evidenceArtifactS3Client") S3Client client,
        EvidenceArtifactStorageProperties properties
    ) {
        return new S3EvidenceArtifactObjectStorage(client, properties);
    }
}
