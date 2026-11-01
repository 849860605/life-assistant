# 后端镜像：Spring Boot 应用 + 飞猪命令行工具（Node）+ PDF 中文字体。
# MIRROR 是镜像站前缀（如 docker.m.daocloud.io/），国内拉不到 Docker Hub 时用；默认直连。
ARG MIRROR=

# 构建阶段
FROM ${MIRROR}library/maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY deploy/maven-settings.xml /root/.m2/settings.xml
COPY pom.xml .
COPY life-common/pom.xml life-common/
COPY life-core/pom.xml life-core/
COPY life-skill-travel/pom.xml life-skill-travel/
COPY life-skill-schedule/pom.xml life-skill-schedule/
COPY life-skill-food/pom.xml life-skill-food/
COPY life-api/pom.xml life-api/
RUN mvn -q -B dependency:go-offline -DexcludeGroupIds=com.lifeassistant || true
COPY . .
RUN mvn -q -B -DskipTests package
# 行程导出 PDF 用的中文字体：项目 data/fonts 里有就直接用，没有再去下载（国内可能下不了，下不了 PDF 用默认字体）
RUN mkdir -p /build/fonts && cp -r data/fonts/. /build/fonts/ 2>/dev/null; \
    tools/fetch-fonts.sh /build/fonts || echo "字体下载失败，PDF 里的中文可能显示不全"

# 飞猪命令行工具（官方只提供命令行，后端通过 FLYAI_COMMAND 调用）
FROM ${MIRROR}library/node:20-bookworm-slim AS tools
ARG NPM_REGISTRY=https://registry.npmmirror.com
WORKDIR /tools
COPY tools/package.json tools/package-lock.json ./
RUN npm ci --omit=dev --registry=${NPM_REGISTRY}

# 运行阶段
FROM ${MIRROR}library/eclipse-temurin:21-jre
ENV TZ=Asia/Shanghai
WORKDIR /app
RUN useradd --system --uid 1001 life && mkdir -p /app/data && chown life /app/data
COPY --from=tools /usr/local/bin/node /usr/local/bin/node
COPY --from=tools /tools/node_modules /app/tools/node_modules
COPY --from=build /build/life-api/target/life-app.jar app.jar
COPY --from=build /build/fonts /app/fonts
COPY eval /app/eval
ENV LIFE_PDF_FONT_DIR=/app/fonts \
    FLYAI_COMMAND=/app/tools/node_modules/.bin/flyai \
    HOME=/app/data
USER life
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080 && printf "GET /actuator/health HTTP/1.0\r\n\r\n" >&3 && grep -q UP <&3'
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
