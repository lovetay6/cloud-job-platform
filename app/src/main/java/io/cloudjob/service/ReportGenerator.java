package io.cloudjob.service;

import io.cloudjob.config.AwsProperties;
import io.cloudjob.domain.ExportJob;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Streams report rows to a bounded temporary file (O(1) heap footprint) before uploading to S3,
 * preventing JVM OOM during large report exports.
 */
@Component
public class ReportGenerator {

    private final S3Client s3Client;
    private final AwsProperties awsProperties;

    public ReportGenerator(S3Client s3Client, AwsProperties awsProperties) {
        this.s3Client = s3Client;
        this.awsProperties = awsProperties;
    }

    public GeneratedReport generateAndUpload(ExportJob job) throws IOException {
        Path tempFile = Files.createTempFile("export-" + job.getId() + "-", ".csv");
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(tempFile, StandardCharsets.UTF_8)) {
                writer.write("row_num,job_id,report_type,account_ref,amount_usd,generated_at\n");
                String now = Instant.now().toString();
                for (int i = 1; i <= job.getRowCount(); i++) {
                    writer.write(i + "," + job.getId() + "," + job.getReportType()
                            + ",ACC-" + String.format("%06d", i)
                            + "," + String.format("%.2f", (i * 17.35) % 10000.0)
                            + "," + now + "\n");
                }
            }

            long sizeBytes = Files.size(tempFile);
            String s3Key = "exports/" + job.getReportType().toLowerCase() + "/" + job.getId() + ".csv";

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(awsProperties.s3Bucket())
                    .key(s3Key)
                    .contentType("text/csv; charset=utf-8")
                    .build();

            s3Client.putObject(putRequest, tempFile);
            return new GeneratedReport(s3Key, sizeBytes);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    public record GeneratedReport(String s3Key, long sizeBytes) {}
}
