package io.cloudjob.web;

import io.cloudjob.domain.ExportJob;
import io.cloudjob.domain.JobStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class JobDtos {

    private JobDtos() {
    }

    public record CreateJobRequest(
            @NotBlank @Size(max = 64) String reportType,
            @Min(1) @Max(500_000) int rowCount,
            @Size(max = 128) String idempotencyKey,
            Boolean simulateFailure
    ) {}

    public record JobResponse(
            UUID id,
            String idempotencyKey,
            String reportType,
            int rowCount,
            boolean simulateFailure,
            JobStatus status,
            int attemptCount,
            String workerId,
            String s3Key,
            Long fileSizeBytes,
            String errorMessage,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt
    ) {
        public static JobResponse from(ExportJob job) {
            return new JobResponse(
                    job.getId(),
                    job.getIdempotencyKey(),
                    job.getReportType(),
                    job.getRowCount(),
                    job.isSimulateFailure(),
                    job.getStatus(),
                    job.getAttemptCount(),
                    job.getWorkerId(),
                    job.getS3Key(),
                    job.getFileSizeBytes(),
                    job.getErrorMessage(),
                    job.getCreatedAt(),
                    job.getStartedAt(),
                    job.getCompletedAt()
            );
        }
    }

    public record DownloadUrlResponse(
            UUID jobId,
            String s3Key,
            Long fileSizeBytes,
            String downloadUrl,
            Instant expiresAt
    ) {}

    public record QueueStatsResponse(
            int mainQueueVisible,
            int mainQueueInFlight,
            int dlqVisible
    ) {}

    public record DlqRedriveResponse(
            String taskHandle,
            String sourceArn,
            String destinationArn
    ) {}

    public record ErrorResponse(
            String error,
            String message,
            Instant timestamp
    ) {}
}
