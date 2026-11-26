package io.cloudjob.service;

import io.cloudjob.config.AwsProperties;
import io.cloudjob.domain.ExportJob;
import io.cloudjob.domain.JobStatus;
import io.cloudjob.repository.ExportJobRepository;
import io.cloudjob.web.JobDtos.CreateJobRequest;
import io.cloudjob.web.JobDtos.DlqRedriveResponse;
import io.cloudjob.web.JobDtos.DownloadUrlResponse;
import io.cloudjob.web.JobDtos.JobResponse;
import io.cloudjob.web.JobDtos.QueueStatsResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.StartMessageMoveTaskRequest;
import software.amazon.awssdk.services.sqs.model.StartMessageMoveTaskResponse;

@Service
public class JobApiService {

    private static final Logger log = LoggerFactory.getLogger(JobApiService.class);

    private final ExportJobRepository repository;
    private final SqsClient sqsClient;
    private final S3Presigner s3Presigner;
    private final AwsProperties awsProperties;

    public JobApiService(
            ExportJobRepository repository,
            SqsClient sqsClient,
            S3Presigner s3Presigner,
            AwsProperties awsProperties
    ) {
        this.repository = repository;
        this.sqsClient = sqsClient;
        this.s3Presigner = s3Presigner;
        this.awsProperties = awsProperties;
    }

    public JobResponse submitJob(CreateJobRequest request, String headerIdempotencyKey) {
        String idempotencyKey = resolveIdempotencyKey(request.idempotencyKey(), headerIdempotencyKey);

        Optional<ExportJob> existing = repository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            log.info("Idempotent hit for key={} returning jobId={}", idempotencyKey, existing.get().getId());
            return JobResponse.from(existing.get());
        }

        ExportJob saved;
        try {
            ExportJob job = ExportJob.createPending(
                    UUID.randomUUID(),
                    idempotencyKey,
                    request.reportType().trim(),
                    request.rowCount(),
                    Boolean.TRUE.equals(request.simulateFailure()),
                    Instant.now()
            );
            saved = repository.saveAndFlush(job);
        } catch (DataIntegrityViolationException duplicateRace) {
            ExportJob winner = repository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> duplicateRace);
            log.info("Concurrent idempotent race resolved for key={} jobId={}", idempotencyKey, winner.getId());
            return JobResponse.from(winner);
        }

        enqueueJob(saved.getId());
        log.info("Created and enqueued export job jobId={} reportType={} rows={} simulateFailure={}",
                saved.getId(), saved.getReportType(), saved.getRowCount(), saved.isSimulateFailure());
        return JobResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public JobResponse getJob(UUID jobId) {
        ExportJob job = repository.findById(jobId)
                .orElseThrow(() -> new NoSuchElementException("Job not found: " + jobId));
        return JobResponse.from(job);
    }

    @Transactional(readOnly = true)
    public List<JobResponse> listRecentJobs(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return repository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, safeLimit))
                .stream()
                .map(JobResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public DownloadUrlResponse generateDownloadUrl(UUID jobId) {
        ExportJob job = repository.findById(jobId)
                .orElseThrow(() -> new NoSuchElementException("Job not found: " + jobId));

        if (job.getStatus() != JobStatus.COMPLETED || job.getS3Key() == null) {
            throw new IllegalStateException("Job is not completed yet: status=" + job.getStatus());
        }

        Duration expiry = Duration.ofSeconds(awsProperties.presignedUrlExpirySeconds());
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(awsProperties.s3Bucket())
                .key(job.getS3Key())
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(expiry)
                .getObjectRequest(getObjectRequest)
                .build();

        PresignedGetObjectRequest presigned = s3Presigner.presignGetObject(presignRequest);
        return new DownloadUrlResponse(
                job.getId(),
                job.getS3Key(),
                job.getFileSizeBytes(),
                presigned.url().toString(),
                Instant.now().plus(expiry)
        );
    }

    @Transactional
    public JobResponse clearSimulatedFailure(UUID jobId) {
        int updated = repository.clearSimulatedFailure(jobId, Instant.now());
        if (updated == 0) {
            throw new NoSuchElementException("Job not found: " + jobId);
        }
        return getJob(jobId);
    }

    public QueueStatsResponse getQueueStats() {
        Map<QueueAttributeName, String> mainAttrs = sqsClient.getQueueAttributes(
                GetQueueAttributesRequest.builder()
                        .queueUrl(awsProperties.sqsQueueUrl())
                        .attributeNames(
                                QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                                QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE
                        )
                        .build()
        ).attributes();

        Map<QueueAttributeName, String> dlqAttrs = sqsClient.getQueueAttributes(
                GetQueueAttributesRequest.builder()
                        .queueUrl(awsProperties.sqsDlqUrl())
                        .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)
                        .build()
        ).attributes();

        return new QueueStatsResponse(
                parseInt(mainAttrs.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)),
                parseInt(mainAttrs.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)),
                parseInt(dlqAttrs.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES))
        );
    }

    /**
     * Triggers AWS native SQS DLQ redrive (StartMessageMoveTask) from DLQ back to main queue.
     */
    public DlqRedriveResponse redriveDlq() {
        String dlqArn = fetchQueueArn(awsProperties.sqsDlqUrl());
        String mainArn = fetchQueueArn(awsProperties.sqsQueueUrl());

        StartMessageMoveTaskResponse response = sqsClient.startMessageMoveTask(
                StartMessageMoveTaskRequest.builder()
                        .sourceArn(dlqArn)
                        .destinationArn(mainArn)
                        .build()
        );
        log.info("Started SQS DLQ redrive taskHandle={} source={} destination={}",
                response.taskHandle(), dlqArn, mainArn);
        return new DlqRedriveResponse(response.taskHandle(), dlqArn, mainArn);
    }

    private void enqueueJob(UUID jobId) {
        sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(awsProperties.sqsQueueUrl())
                .messageBody(jobId.toString())
                .build());
    }

    private String fetchQueueArn(String queueUrl) {
        return sqsClient.getQueueAttributes(
                GetQueueAttributesRequest.builder()
                        .queueUrl(queueUrl)
                        .attributeNames(QueueAttributeName.QUEUE_ARN)
                        .build()
        ).attributes().get(QueueAttributeName.QUEUE_ARN);
    }

    private String resolveIdempotencyKey(String bodyKey, String headerKey) {
        if (headerKey != null && !headerKey.isBlank()) {
            return headerKey.trim();
        }
        if (bodyKey != null && !bodyKey.isBlank()) {
            return bodyKey.trim();
        }
        return UUID.randomUUID().toString();
    }

    private int parseInt(String value) {
        return value == null ? 0 : Integer.parseInt(value);
    }
}
