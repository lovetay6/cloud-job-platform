# Build Java bytecode at native host speed ($BUILDPLATFORM = amd64 on GitHub Actions)
# then package into an ARM64 (AWS Graviton) JRE 21 image without slow QEMU compilation.
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /workspace
COPY app/pom.xml ./pom.xml
RUN mvn -B -q dependency:go-offline

COPY app/src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy

RUN groupadd -r app && useradd -r -g app -d /app -s /sbin/nologin appuser
WORKDIR /app

COPY --from=builder /workspace/target/cloud-job-platform-*.jar /app/app.jar
RUN chown -R appuser:app /app

USER appuser
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
