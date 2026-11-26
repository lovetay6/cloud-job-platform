package io.cloudjob.web;

import io.cloudjob.config.AwsProperties;
import io.cloudjob.service.JobApiService;
import io.cloudjob.web.JobDtos.CreateJobRequest;
import io.cloudjob.web.JobDtos.DlqRedriveResponse;
import io.cloudjob.web.JobDtos.DownloadUrlResponse;
import io.cloudjob.web.JobDtos.JobResponse;
import io.cloudjob.web.JobDtos.QueueStatsResponse;
import jakarta.validation.Valid;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class JobController {

    private final JobApiService jobApiService;
    private final AwsProperties awsProperties;

    public JobController(JobApiService jobApiService, AwsProperties awsProperties) {
        this.jobApiService = jobApiService;
        this.awsProperties = awsProperties;
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown";
        }
        return Map.of(
                "service", "cloud-job-platform",
                "release", "v1.1.0-rolling-deploy",
                "region", awsProperties.region(),
                "host", host
        );
    }

    @PostMapping("/jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobResponse createJob(
            @Valid @RequestBody CreateJobRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return jobApiService.submitJob(request, idempotencyKey);
    }

    @GetMapping("/jobs/{jobId}")
    public JobResponse getJob(@PathVariable UUID jobId) {
        return jobApiService.getJob(jobId);
    }

    @GetMapping("/jobs")
    public List<JobResponse> listJobs(@RequestParam(defaultValue = "20") int limit) {
        return jobApiService.listRecentJobs(limit);
    }

    @GetMapping("/jobs/{jobId}/download-url")
    public DownloadUrlResponse getDownloadUrl(@PathVariable UUID jobId) {
        return jobApiService.generateDownloadUrl(jobId);
    }

    @PostMapping("/jobs/{jobId}/clear-simulated-failure")
    public JobResponse clearSimulatedFailure(@PathVariable UUID jobId) {
        return jobApiService.clearSimulatedFailure(jobId);
    }

    @GetMapping("/admin/queues")
    public QueueStatsResponse getQueueStats() {
        return jobApiService.getQueueStats();
    }

    @PostMapping("/admin/dlq/redrive")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DlqRedriveResponse redriveDlq() {
        return jobApiService.redriveDlq();
    }
}
