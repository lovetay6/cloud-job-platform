package io.cloudjob.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.cloudjob.config.AwsProperties;
import io.cloudjob.domain.ExportJob;
import io.cloudjob.repository.ExportJobRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;

class JobWorkerServiceTest {

    private ExportJobRepository repository;
    private SqsClient sqsClient;
    private ReportGenerator reportGenerator;
    private JobWorkerService workerService;

    @BeforeEach
    void setUp() {
        repository = mock(ExportJobRepository.class);
        sqsClient = mock(SqsClient.class);
        reportGenerator = mock(ReportGenerator.class);

        TransactionTemplate txTemplate = new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(mock(TransactionStatus.class));
            }
        };

        AwsProperties props = new AwsProperties(
                "ap-southeast-2",
                "test-bucket",
                "https://sqs.ap-southeast-2.amazonaws.com/097089670186/test-q",
                "https://sqs.ap-southeast-2.amazonaws.com/097089670186/test-dlq",
                300,
                new AwsProperties.WorkerProperties(false, 20, 5, 60, 4)
        );
        workerService = new JobWorkerService(repository, sqsClient, reportGenerator, props, txTemplate);
    }

    @Test
    void happyPathClaimsGeneratesUploadsAndAcksMessage() throws Exception {
        UUID jobId = UUID.randomUUID();
        ExportJob job = ExportJob.createPending(jobId, "k-1", "SETTLEMENT", 100, false, Instant.now());

        when(repository.tryClaimJob(eq(jobId), anyString(), any(), any())).thenReturn(1);
        when(repository.findById(jobId)).thenReturn(Optional.of(job));
        when(reportGenerator.generateAndUpload(job))
                .thenReturn(new ReportGenerator.GeneratedReport("exports/settlement/" + jobId + ".csv", 4096L));
        when(repository.markCompleted(eq(jobId), anyString(), anyString(), anyLong(), any())).thenReturn(1);

        Message msg = Message.builder().messageId("m-1").receiptHandle("rh-1").body(jobId.toString()).build();
        workerService.processMessage(msg);

        verify(reportGenerator, times(1)).generateAndUpload(job);
        verify(sqsClient, times(1)).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void simulatedFailureMarksFailedAndLeavesMessageForDlq() throws Exception {
        UUID jobId = UUID.randomUUID();
        ExportJob job = ExportJob.createPending(jobId, "k-fail", "SETTLEMENT", 100, true, Instant.now());

        when(repository.tryClaimJob(eq(jobId), anyString(), any(), any())).thenReturn(1);
        when(repository.findById(jobId)).thenReturn(Optional.of(job));
        when(repository.markFailed(eq(jobId), anyString(), anyString(), any())).thenReturn(1);

        Message msg = Message.builder().messageId("m-fail").receiptHandle("rh-fail").body(jobId.toString()).build();
        workerService.processMessage(msg);

        verify(reportGenerator, never()).generateAndUpload(any());
        verify(repository, times(1)).markFailed(eq(jobId), anyString(), anyString(), any());
        verify(sqsClient, never()).deleteMessage(any(DeleteMessageRequest.class));
    }
}
