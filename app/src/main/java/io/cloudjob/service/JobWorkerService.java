package io.cloudjob.service;

import io.cloudjob.config.AwsProperties;
import io.cloudjob.domain.ExportJob;
import io.cloudjob.domain.JobStatus;
import io.cloudjob.repository.ExportJobRepository;
import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

/**
 * SQS long-polling worker with:
 * 1. Bounded concurrency backpressure (Semaphore)
 * 2. Database CAS claim + fencing token (deduplicates at-least-once SQS delivery &amp; reclaims expired leases)
 * 3. S3 streaming I/O strictly outside DB transactions (protects HikariCP connection pool)
 * 4. Graceful SIGTERM drain via SmartLifecycle for zero-message-loss ECS rolling deployments
 */
@Service
public class JobWorkerService implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(JobWorkerService.class);

    private final ExportJobRepository repository;
    private final SqsClient sqsClient;
    private final ReportGenerator reportGenerator;
    private final AwsProperties awsProperties;
    private final TransactionTemplate transactionTemplate;
    private final String workerId;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService pollerExecutor;
    private ExecutorService taskExecutor;
    private Semaphore backpressureSemaphore;

    public JobWorkerService(
            ExportJobRepository repository,
            SqsClient sqsClient,
            ReportGenerator reportGenerator,
            AwsProperties awsProperties,
            TransactionTemplate transactionTemplate
    ) {
        this.repository = repository;
        this.sqsClient = sqsClient;
        this.reportGenerator = reportGenerator;
        this.awsProperties = awsProperties;
        this.transactionTemplate = transactionTemplate;
        this.workerId = resolveWorkerId();
    }

    @Override
    public void start() {
        if (!awsProperties.worker().enabled()) {
            log.info("SQS worker is disabled in this mode (app.aws.worker.enabled=false)");
            return;
        }
        if (running.compareAndSet(false, true)) {
            int maxConcurrency = Math.max(1, awsProperties.worker().maxConcurrency());
            this.backpressureSemaphore = new Semaphore(maxConcurrency);
            this.pollerExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "sqs-poller"));
            this.taskExecutor = Executors.newVirtualThreadPerTaskExecutor();
            this.pollerExecutor.submit(this::pollLoop);
            log.info("Started SQS worker workerId={} queueUrl={} maxConcurrency={}",
                    workerId, awsProperties.sqsQueueUrl(), maxConcurrency);
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping SQS worker workerId={}, draining in-flight jobs...", workerId);
            if (pollerExecutor != null) {
                pollerExecutor.shutdownNow();
            }
            if (taskExecutor != null) {
                taskExecutor.shutdown();
                try {
                    if (!taskExecutor.awaitTermination(25, TimeUnit.SECONDS)) {
                        log.warn("Timed out waiting for in-flight jobs to drain");
                        taskExecutor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    taskExecutor.shutdownNow();
                }
            }
            log.info("SQS worker stopped cleanly workerId={}", workerId);
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void pollLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                // Apply backpressure before pulling more messages from SQS
                int availableSlots = backpressureSemaphore.availablePermits();
                if (availableSlots <= 0) {
                    Thread.sleep(200);
                    continue;
                }
                int batchSize = Math.min(availableSlots, Math.min(10, Math.max(1, awsProperties.worker().maxMessages())));

                List<Message> messages = sqsClient.receiveMessage(
                        ReceiveMessageRequest.builder()
                                .queueUrl(awsProperties.sqsQueueUrl())
                                .maxNumberOfMessages(batchSize)
                                .waitTimeSeconds(awsProperties.worker().waitTimeSeconds())
                                .build()
                ).messages();

                for (Message message : messages) {
                    backpressureSemaphore.acquire();
                    taskExecutor.submit(() -> {
                        try {
                            processMessage(message);
                        } finally {
                            backpressureSemaphore.release();
                        }
                    });
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (running.get()) {
                    log.error("Error polling SQS queue, backing off 3s", e);
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    /**
     * Package-private for direct deterministic unit testing.
     */
    void processMessage(Message message) {
        UUID jobId;
        try {
            jobId = UUID.fromString(message.body().trim());
        } catch (IllegalArgumentException invalidBody) {
            log.error("Invalid jobId payload in SQS messageId={} body={}, leaving for DLQ",
                    message.messageId(), message.body());
            return;
        }

        Instant now = Instant.now();
        Instant leaseUntil = now.plusSeconds(awsProperties.worker().leaseSeconds());

        Integer claimed = transactionTemplate.execute(status ->
                repository.tryClaimJob(jobId, workerId, now, leaseUntil)
        );

        if (claimed == null || claimed == 0) {
            Optional<ExportJob> current = repository.findById(jobId);
            if (current.isEmpty() || current.get().getStatus() == JobStatus.COMPLETED) {
                log.info("Skipping duplicate/completed SQS delivery jobId={} messageId={}", jobId, message.messageId());
                deleteMessage(message);
            } else {
                log.info("Job currently held by active worker jobId={} holder={} leaseUntil={}",
                        jobId, current.get().getWorkerId(), current.get().getLeaseUntil());
            }
            return;
        }

        ExportJob job = repository.findById(jobId).orElseThrow();
        try {
            if (job.isSimulateFailure()) {
                throw new IllegalStateException("Simulated export failure for DLQ & CloudWatch Alarm drill");
            }

            // Perform S3 streaming I/O strictly outside DB transaction
            ReportGenerator.GeneratedReport generated = reportGenerator.generateAndUpload(job);

            Integer finalized = transactionTemplate.execute(status ->
                    repository.markCompleted(jobId, workerId, generated.s3Key(), generated.sizeBytes(), Instant.now())
            );

            if (finalized != null && finalized == 1) {
                deleteMessage(message);
                log.info("Completed export job jobId={} attempt={} s3Key={} sizeBytes={}",
                        jobId, job.getAttemptCount(), generated.s3Key(), generated.sizeBytes());
            } else {
                log.warn("Lost lease fencing before finalizing jobId={} workerId={}", jobId, workerId);
            }
        } catch (Exception ex) {
            String truncated = truncateError(ex.getMessage());
            transactionTemplate.executeWithoutResult(status ->
                    repository.markFailed(jobId, workerId, truncated, Instant.now())
            );
            // Deliberately do NOT delete SQS message so visibility timeout triggers retry -> DLQ
            log.error("Export job failed jobId={} attempt={} error={}, leaving message for SQS retry/DLQ",
                    jobId, job.getAttemptCount(), truncated);
        }
    }

    private void deleteMessage(Message message) {
        sqsClient.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(awsProperties.sqsQueueUrl())
                .receiptHandle(message.receiptHandle())
                .build());
    }

    private String truncateError(String msg) {
        if (msg == null) {
            return "Unknown error";
        }
        return msg.length() > 950 ? msg.substring(0, 950) : msg;
    }

    private String resolveWorkerId() {
        try {
            return InetAddress.getLocalHost().getHostName() + "-" + UUID.randomUUID().toString().substring(0, 8);
        } catch (Exception e) {
            return "worker-" + UUID.randomUUID().toString().substring(0, 8);
        }
    }
}
