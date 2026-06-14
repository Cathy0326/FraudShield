# ── Stage 1: Build ────────────────────────────────────────────────────────────
# 使用完整JDK+Maven编译，不包含在最终镜像中
# Use full JDK + Maven to compile; this stage is discarded after build
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# 先复制pom.xml并下载依赖（利用Docker层缓存）
# Copy pom.xml first and resolve dependencies — Docker caches this layer
# unless pom.xml changes, making rebuilds much faster
COPY pom.xml .
RUN mvn dependency:go-offline -q

# 再复制源码并打包（依赖缓存命中时只重新编译源码）
# Copy source and package — dependency layer above stays cached
COPY src ./src
RUN mvn package -DskipTests -q

# ── Stage 2: Run ──────────────────────────────────────────────────────────────
# 只包含JRE运行环境，最终镜像约200MB
# Only JRE in final image (~200MB vs ~600MB with full JDK + Maven)
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# 从构建阶段复制打包好的jar
# Copy the jar from the build stage — source code stays out of this image
COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
