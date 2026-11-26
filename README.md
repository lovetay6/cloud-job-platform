# Cloud Job Platform (Java 21 + AWS ECS Fargate + Terraform)

A production-grade asynchronous report export and job processing platform built with **Java 21**, **Spring Boot 3**, **AWS SDK v2**, **Terraform**, and **GitHub Actions**, deployed 1:1 on real **Amazon Web Services**.

## Architecture

```mermaid
flowchart LR
    Client["Client / API Caller"] -->|HTTP 80| ALB["Application Load Balancer"]
    ALB -->|TCP 8080 (ALB SG only)| API["ECS Fargate API Service (ARM64)"]
    API -->|Idempotent Write| RDS[("RDS PostgreSQL 16 (Private Subnets)")]
    API -->|Enqueue Job| SQS["Amazon SQS Main Queue"]
    API -->|Presign GET| S3["Amazon S3 Exports Bucket (Private + SSE)"]
    SQS -->|Long Poll + Backpressure| Worker["ECS Fargate Worker Service (ARM64)"]
    Worker -->|CAS Claim & Lease Fencing| RDS
    Worker -->|Streaming Upload| S3
    SQS -->|After 3 Retries| DLQ["SQS Dead Letter Queue"]
    DLQ -->|Visible >= 1| CW["CloudWatch Logs & Alarms"]
    SSM["SSM Parameter Store (SecureString)"] -.->|Runtime Secret Injection| API
    SSM -.->|Runtime Secret Injection| Worker
```

## Production Engineering Highlights

- **Zero Hardcoded AWS Credentials**: Runtime uses the AWS SDK v2 `DefaultCredentialsProvider` backed by a least-privilege **ECS Task Role** (scoped only to this S3 bucket and SQS queues). Database credentials are stored in **SSM Parameter Store (`SecureString`)** and injected at container boot by ECS.
- **At-Least-Once Idempotency & Orphan Recovery**:
  - API submission enforces unique `idempotency_key` semantics (`Idempotency-Key` header or request field) with concurrent race handling.
  - Worker claims jobs using an atomic PostgreSQL **CAS state transition** (`PENDING/FAILED` or expired `RUNNING` lease) with `worker_id` fencing tokens so duplicate SQS deliveries never double-process and crashed containers are cleanly recovered.
- **O(1) Memory Export & Zero-App-Bandwidth Downloads**: Reports stream directly to disk and upload to S3 **strictly outside database transactions** (protecting HikariCP connections), and clients download files via short-lived **S3 Pre-signed URLs**.
- **Bounded Concurrency & Graceful Shutdown**: Worker uses Java 21 virtual threads bounded by a `Semaphore` backpressure limit and `SmartLifecycle` SIGTERM draining (`stopTimeout = 30s`) paired with ECS Deployment Circuit Breaker automatic rollback.
- **Cost-Controlled 2-Layer Terraform**:
  - `infra/bootstrap`: Persistent zero-cost ECR repository (lifecycle max 5 images) + least-privilege GitHub Actions deployer.
  - `infra/env/dev`: On-demand runtime stack (2-AZ VPC, strict 3-tier Security Groups, Private-subnet RDS PostgreSQL 16, S3, SQS+DLQ, ALB, ARM64 Fargate services, CloudWatch Alarms). Tear down cleanly with `terraform destroy` between sessions.

## Quick Start

### 1. Run Local Unit & CAS Repository Tests
```bash
cd app
mvn test
```

### 2. Provision Persistent Bootstrap Layer (ECR + CI Deployer)
```bash
cd infra/bootstrap
terraform init
terraform apply
```
Copy `ci_access_key_id` and `ci_secret_access_key` (`terraform output -raw ...`) into your GitHub repository Secrets (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`), then push to `main` so GitHub Actions builds and pushes the ARM64 image to ECR.

### 3. Provision Runtime Environment on AWS
```bash
cd infra/env/dev
terraform init
terraform apply
```

### 4. End-to-End API & DLQ Incident Drills
```bash
ALB=$(terraform -chdir=infra/env/dev output -raw alb_base_url)

# Health check
curl "$ALB/actuator/health"

# Submit idempotent export job (50,000 rows)
curl -X POST "$ALB/api/v1/jobs" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-001" \
  -d '{"reportType":"SETTLEMENT","rowCount":50000}'

# Check status & obtain S3 Pre-signed download URL
curl "$ALB/api/v1/jobs/<job-id>"
curl "$ALB/api/v1/jobs/<job-id>/download-url"

# DLQ + CloudWatch Alarm + Redrive Drill
curl -X POST "$ALB/api/v1/jobs" \
  -H "Content-Type: application/json" \
  -d '{"reportType":"AUDIT","rowCount":100,"simulateFailure":true}'
# Wait ~3 retries -> inspect queue stats & CloudWatch Alarm
curl "$ALB/api/v1/admin/queues"
# Clear failure flag and trigger AWS native SQS DLQ Redrive
curl -X POST "$ALB/api/v1/jobs/<failed-job-id>/clear-simulated-failure"
curl -X POST "$ALB/api/v1/admin/dlq/redrive"
```

### 5. Tear Down Runtime Stack (Preserve Credits)
```bash
cd infra/env/dev
terraform destroy
```
