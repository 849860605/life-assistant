# 构建阶段
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY life-common/pom.xml life-common/
COPY life-core/pom.xml life-core/
COPY life-skill-travel/pom.xml life-skill-travel/
COPY life-skill-schedule/pom.xml life-skill-schedule/
COPY life-api/pom.xml life-api/
RUN mvn -q -B dependency:go-offline -DexcludeArtifactIds=life-common,life-core,life-skill-travel,life-skill-schedule || true
COPY . .
RUN mvn -q -B -DskipTests package
# 行程导出 PDF 用的中文字体（Noto Sans SC，开源授权），构建时下载，不放进代码仓库
RUN tools/fetch-fonts.sh /build/fonts

# 运行阶段
FROM eclipse-temurin:21-jre
ENV TZ=Asia/Shanghai
WORKDIR /app
RUN useradd --system --uid 1001 life && mkdir -p /app/data && chown life /app/data
COPY --from=build /build/life-api/target/life-app.jar app.jar
COPY --from=build /build/fonts /app/fonts
ENV LIFE_PDF_FONT_DIR=/app/fonts
COPY eval /app/eval
USER life
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
