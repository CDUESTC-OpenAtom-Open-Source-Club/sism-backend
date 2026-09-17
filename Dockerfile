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

FROM eclipse-temurin:17-jre-alpine AS runtime

# bash: backend-entrypoint.sh 是 bash 语法
# postgresql-client: entrypoint 用 pg_isready 等待数据库就绪
# tzdata: 提供 Asia/Shanghai 时区数据（alpine 默认不含）
RUN apk add --no-cache bash postgresql-client tzdata

# 统一以北京时间运行：jackson 的 time-zone/date-format 只作用于 java.util.Date，
# 对 LocalDateTime（全仓主力类型）无效；必须让 JVM 时区本身为 Asia/Shanghai，
# 否则 LocalDateTime.now() 落 UTC，接口时间比北京时间少 8 小时。
ENV TZ=Asia/Shanghai

WORKDIR /app

COPY --from=build /workspace/layers/dependencies/ ./
COPY --from=build /workspace/layers/spring-boot-loader/ ./
COPY --from=build /workspace/layers/snapshot-dependencies/ ./
COPY --from=build /workspace/layers/application/ ./
COPY docker/backend-entrypoint.sh /app/backend-entrypoint.sh

RUN chmod +x /app/backend-entrypoint.sh

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=10 \
  CMD wget -q -O /dev/null http://localhost:8080/api/v1/actuator/health || exit 1

ENTRYPOINT ["/app/backend-entrypoint.sh"]
