FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /workspace

COPY pom.xml ./
COPY sism-shared-kernel/pom.xml sism-shared-kernel/pom.xml
COPY sism-iam/pom.xml sism-iam/pom.xml
COPY sism-organization/pom.xml sism-organization/pom.xml
COPY sism-strategy/pom.xml sism-strategy/pom.xml
COPY sism-task/pom.xml sism-task/pom.xml
COPY sism-workflow/pom.xml sism-workflow/pom.xml
COPY sism-execution/pom.xml sism-execution/pom.xml
COPY sism-analytics/pom.xml sism-analytics/pom.xml
COPY sism-alert/pom.xml sism-alert/pom.xml
COPY sism-main/pom.xml sism-main/pom.xml

RUN mvn -B -pl sism-main -am dependency:go-offline

COPY . .

RUN mvn -B -pl sism-main -am package -Dmaven.test.skip=true -Dmaven.javadoc.skip=true
# 版本无关：定位实际产出的可执行 jar（finalName 可能带版本号）
RUN JAR=$(ls /workspace/sism-main/target/sism-main-*.jar | grep -v '\.original' | head -1) \
    && java -Djarmode=layertools -jar "$JAR" extract --destination /workspace/layers

FROM eclipse-temurin:17-jre-jammy AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl postgresql-client \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=build /workspace/layers/dependencies/ ./
COPY --from=build /workspace/layers/spring-boot-loader/ ./
COPY --from=build /workspace/layers/snapshot-dependencies/ ./
COPY --from=build /workspace/layers/application/ ./
COPY docker/backend-entrypoint.sh /app/backend-entrypoint.sh

RUN chmod +x /app/backend-entrypoint.sh

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=10 \
  CMD curl -fsS http://localhost:8080/api/v1/actuator/health || exit 1

ENTRYPOINT ["/app/backend-entrypoint.sh"]
