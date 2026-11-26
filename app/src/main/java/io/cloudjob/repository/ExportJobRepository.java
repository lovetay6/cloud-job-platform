package io.cloudjob.repository;

import io.cloudjob.domain.ExportJob;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExportJobRepository extends JpaRepository<ExportJob, UUID> {

    Optional<ExportJob> findByIdempotencyKey(String idempotencyKey);

    List<ExportJob> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * Atomic compare-and-set (CAS) job claim.
     * Prevents duplicate execution under SQS at-least-once delivery AND supports
     * orphan recovery if a previous worker crashed and its lease expired.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ExportJob j
               SET j.status = io.cloudjob.domain.JobStatus.RUNNING,
                   j.attemptCount = j.attemptCount + 1,
                   j.workerId = :workerId,
                   j.leaseUntil = :leaseUntil,
                   j.startedAt = COALESCE(j.startedAt, :now),
                   j.errorMessage = NULL,
                   j.updatedAt = :now
             WHERE j.id = :jobId
               AND (
                    j.status = io.cloudjob.domain.JobStatus.PENDING
                    OR j.status = io.cloudjob.domain.JobStatus.FAILED
                    OR (j.status = io.cloudjob.domain.JobStatus.RUNNING AND j.leaseUntil < :now)
               )
            """)
    int tryClaimJob(
            @Param("jobId") UUID jobId,
            @Param("workerId") String workerId,
            @Param("now") Instant now,
            @Param("leaseUntil") Instant leaseUntil
    );

    /**
     * Fenced completion transition: only the worker currently owning the job can finalize it.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ExportJob j
               SET j.status = io.cloudjob.domain.JobStatus.COMPLETED,
                   j.s3Key = :s3Key,
                   j.fileSizeBytes = :fileSizeBytes,
                   j.leaseUntil = NULL,
                   j.errorMessage = NULL,
                   j.completedAt = :now,
                   j.updatedAt = :now
             WHERE j.id = :jobId
               AND j.status = io.cloudjob.domain.JobStatus.RUNNING
               AND j.workerId = :workerId
            """)
    int markCompleted(
            @Param("jobId") UUID jobId,
            @Param("workerId") String workerId,
            @Param("s3Key") String s3Key,
            @Param("fileSizeBytes") long fileSizeBytes,
            @Param("now") Instant now
    );

    /**
     * Records a failure while preserving fencing; the SQS message stays un-deleted
     * so SQS retries according to visibility timeout and routes to DLQ after maxReceiveCount.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ExportJob j
               SET j.status = io.cloudjob.domain.JobStatus.FAILED,
                   j.errorMessage = :errorMessage,
                   j.leaseUntil = NULL,
                   j.updatedAt = :now
             WHERE j.id = :jobId
               AND j.status = io.cloudjob.domain.JobStatus.RUNNING
               AND j.workerId = :workerId
            """)
    int markFailed(
            @Param("jobId") UUID jobId,
            @Param("workerId") String workerId,
            @Param("errorMessage") String errorMessage,
            @Param("now") Instant now
    );

    /**
     * Clears simulateFailure so a DLQ-redriven job can succeed during incident recovery drills.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ExportJob j
               SET j.simulateFailure = false,
                   j.updatedAt = :now
             WHERE j.id = :jobId
            """)
    int clearSimulatedFailure(@Param("jobId") UUID jobId, @Param("now") Instant now);
}
