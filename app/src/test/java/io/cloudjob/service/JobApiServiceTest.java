package io.cloudjob.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.cloudjob.config.AwsProperties;
import io.cloudjob.domain.ExportJob;
import io.cloudjob.repository.ExportJobRepository;
import io.cloudjob.web.JobDtos.CreateJobRequest;
import io.cloudjob.web.JobDtos.JobResponse;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

class JobApiServiceTest {

    private ExportJobRepository repository;
    private SqsClient sqsClient;
    private S3Presigner s3Presigner;
    private JobApiService service;

    @BeforeEach
    void setUp() {
        repository = mock(ExportJobRepository.class);
        sqsClient = mock(SqsClient.class);
        s3Presigner = mock(S3Presigner.class);
        AwsProperties props = new AwsProperties(
                "ap-southeast-2",
                "test-bucket",
                "https://sqs.ap-southeast-2.amazonaws.com/097089670186/test-q",
                "https://sqs.ap-southeast-2.amazonaws.com/097089670186/test-dlq",
                300,
                new AwsProperties.WorkerProperties(false, 20, 5, 60, 4)
        );
        service = new JobApiService(repository, sqsClient, s3Presigner, props);
    }

    @Test
    void submitJobEnqueuesOnceAndDeduplicatesByIdempotencyKey() {
        UUID existingId = UUID.randomUUID();
        ExportJob existing = ExportJob.createPending(existingId, "req-001", "LEDGER", 500, false, Instant.now());

        when(repository.findByIdempotencyKey("req-001")).thenReturn(Optional.of(existing));

        JobResponse response = service.submitJob(new CreateJobRequest("LEDGER", 500, null, false), "req-001");

        assertThat(response.id()).isEqualTo(existingId);
        verify(repository, never()).saveAndFlush(any());
        verify(sqsClient, never()).sendMessage(any(SendMessageRequest.class));
    }

    @Test
    void submitNewJobPersistsAndEnqueuesSqsMessage() {
        when(repository.findByIdempotencyKey("req-002")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(ExportJob.class))).thenAnswer(inv -> inv.getArgument(0));
        when(sqsClient.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("msg-1").build());

        JobResponse response = service.submitJob(new CreateJobRequest("LEDGER", 200, "req-002", false), null);

        assertThat(response.idempotencyKey()).isEqualTo("req-002");
        assertThat(response.rowCount()).isEqualTo(200);
        verify(sqsClient, times(1)).sendMessage(any(SendMessageRequest.class));
    }

    @Test
    void generateDownloadUrlRejectsIncompleteJob() {
        UUID jobId = UUID.randomUUID();
        ExportJob pending = ExportJob.createPending(jobId, "req-003", "LEDGER", 100, false, Instant.now());
        when(repository.findById(jobId)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.generateDownloadUrl(jobId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not completed");
    }
}
