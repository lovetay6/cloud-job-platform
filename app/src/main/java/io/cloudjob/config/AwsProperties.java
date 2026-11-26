package io.cloudjob.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.aws")
public record AwsProperties(
        String region,
        String s3Bucket,
        String sqsQueueUrl,
        String sqsDlqUrl,
        int presignedUrlExpirySeconds,
        WorkerProperties worker
) {
    public record WorkerProperties(
            boolean enabled,
            int waitTimeSeconds,
            int maxMessages,
            int leaseSeconds,
            int maxConcurrency
    ) {}
}
