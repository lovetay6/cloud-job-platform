package io.cloudjob.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.cloudjob.domain.ExportJob;
import io.cloudjob.domain.JobStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ExportJobRepositoryTest {

    @Autowired
    private ExportJobRepository repository;

    @Test
    void casClaimPreventsDuplicateAndSupportsOrphanRecoveryWithFencing() {
        Instant t0 = Instant.parse("2026-11-26T10:00:00Z");
        UUID jobId = UUID.randomUUID();
        repository.saveAndFlush(ExportJob.createPending(jobId, "idem-cas-1", "SETTLEMENT", 1000, false, t0));

        // Worker A claims PENDING job
        int claimA = repository.tryClaimJob(jobId, "worker-a", t0.plusSeconds(1), t0.plusSeconds(61));
        assertThat(claimA).isEqualTo(1);

        // Concurrent duplicate delivery to Worker B while lease is still active must fail CAS
        int claimB = repository.tryClaimJob(jobId, "worker-b", t0.plusSeconds(10), t0.plusSeconds(70));
        assertThat(claimB).isEqualTo(0);

        // After lease expires, Worker C reclaims the orphaned job
        int claimC = repository.tryClaimJob(jobId, "worker-c", t0.plusSeconds(65), t0.plusSeconds(125));
        assertThat(claimC).isEqualTo(1);

        ExportJob reclaimed = repository.findById(jobId).orElseThrow();
        assertThat(reclaimed.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(reclaimed.getAttemptCount()).isEqualTo(2);
        assertThat(reclaimed.getWorkerId()).isEqualTo("worker-c");

        // Stale Worker A cannot overwrite state due to workerId fencing
        int staleComplete = repository.markCompleted(jobId, "worker-a", "exports/stale.csv", 10L, t0.plusSeconds(66));
        assertThat(staleComplete).isEqualTo(0);

        // Current lease holder Worker C finalizes cleanly
        int validComplete = repository.markCompleted(jobId, "worker-c", "exports/valid.csv", 2048L, t0.plusSeconds(67));
        assertThat(validComplete).isEqualTo(1);

        ExportJob completed = repository.findById(jobId).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(completed.getS3Key()).isEqualTo("exports/valid.csv");
        assertThat(completed.getFileSizeBytes()).isEqualTo(2048L);
    }
}
