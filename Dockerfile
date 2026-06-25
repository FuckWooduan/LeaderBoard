# =============================================================================
# rank-harvester — Docker 多阶段构建
# 构建：docker build -t rank-harvester .
# =============================================================================

# ── Build Stage ──────────────────────────────────────────────────────────────
# 使用含 JDK 25 的基础镜像，通过项目自带的 Gradle Wrapper 构建 bootJar。
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /build

# 优先拷贝 wrapper 脚本及 Gradle 元信息，利用 Docker 层缓存加速重复构建
COPY gradlew gradlew.bat gradle.properties settings.gradle build.gradle ./
COPY gradle/ gradle/

# 拷贝全部源码（.dockerignore 已排除 build/、.gradle/ 等无关目录）
COPY src/ src/
COPY config/ config/

RUN sed -i 's/\r$//' gradlew && chmod +x gradlew

# 挂载 Gradle 缓存目录，避免每次重新下载依赖（BuildKit）
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon bootJar

# ── Runtime Stage ────────────────────────────────────────────────────────────
# 仅需 JRE 运行 Spring Boot fat jar，比 JDK 镜像节省约 200MB。
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

LABEL org.opencontainers.image.title="rank-harvester" \
      org.opencontainers.image.description="排行榜抓取与 QQ 推送服务"

# 从构建阶段复制 fat jar（bootJar 默认输出到 build/libs/）
COPY --from=builder /build/build/libs/*.jar app.jar

# 创建数据目录（DuckDB 文件、运行时缓存）
RUN mkdir -p /app/data /app/config

# actuator 健康检查端口
EXPOSE 8080

# 容器健康检查：探测 Spring Boot Actuator health 端点
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", \
    "--enable-preview", \
    "-Dfile.encoding=UTF-8", \
    "-Duser.timezone=Asia/Shanghai", \
    "-jar", "app.jar"]
