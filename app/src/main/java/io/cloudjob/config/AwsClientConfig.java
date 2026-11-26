package io.cloudjob.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Configures AWS SDK v2 clients using the DefaultCredentialsProvider chain.
 * In ECS Fargate, credentials are automatically sourced from the attached IAM Task Role
 * (zero static access keys in code or environment variables).
 */
@Configuration
@ConditionalOnProperty(name = "app.aws.clients-enabled", havingValue = "true", matchIfMissing = true)
public class AwsClientConfig {

    @Bean
    public S3Client s3Client(AwsProperties props) {
        return S3Client.builder()
                .region(Region.of(props.region()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @Bean
    public S3Presigner s3Presigner(AwsProperties props) {
        return S3Presigner.builder()
                .region(Region.of(props.region()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @Bean
    public SqsClient sqsClient(AwsProperties props) {
        return SqsClient.builder()
                .region(Region.of(props.region()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}
