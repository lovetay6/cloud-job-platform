package io.cloudjob.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "export_jobs")
public class ExportJob {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 128)
    private String idempotencyKey;

    @Column(name = "report_type", nullable = false, length = 64)
    private String reportType;

    @Column(name = "row_count", nullable = false)
    private int rowCount;

    @Column(name = "simulate_failure", nullable = false)
    private boolean simulateFailure;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private JobStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "worker_id", length = 128)
    private String workerId;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "s3_key", length = 512)
    private String s3Key;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ExportJob() {
    }

    public static ExportJob createPending(
            UUID id,
            String idempotencyKey,
            String reportType,
            int rowCount,
            boolean simulateFailure,
            Instant now
    ) {
        ExportJob job = new ExportJob();
        job.id = id;
        job.idempotencyKey = idempotencyKey;
        job.reportType = reportType;
        job.rowCount = rowCount;
        job.simulateFailure = simulateFailure;
        job.status = JobStatus.PENDING;
        job.attemptCount = 0;
        job.createdAt = now;
        job.updatedAt = now;
        return job;
    }

    public UUID getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getReportType() {
        return reportType;
    }

    public int getRowCount() {
        return rowCount;
    }

    public boolean isSimulateFailure() {
        return simulateFailure;
    }

    public JobStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public String getWorkerId() {
        return workerId;
    }

    public Instant getLeaseUntil() {
        return leaseUntil;
    }

    public String getS3Key() {
        return s3Key;
    }

    public Long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
