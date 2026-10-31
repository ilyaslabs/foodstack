# ilyaslabs/foodstack-server: the FoodStack server for Kubernetes (TDD 18). Build after
# `mvn package`:  docker build -t ilyaslabs/foodstack-server app/foodstack-server
FROM eclipse-temurin:25-jre AS layers
WORKDIR /build
COPY target/foodstack-server-*-exec.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:25-jre
RUN groupadd --system --gid 10001 foodstack && useradd --system --uid 10001 --gid foodstack --no-create-home foodstack
WORKDIR /app
COPY --from=layers /build/extracted/dependencies/ ./
COPY --from=layers /build/extracted/spring-boot-loader/ ./
COPY --from=layers /build/extracted/snapshot-dependencies/ ./
COPY --from=layers /build/extracted/application/ ./
USER 10001:10001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 --enable-native-access=ALL-UNNAMED"
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=30 \
  CMD ["bash", "-c", "exec 3<>/dev/tcp/localhost/8080 && printf 'GET /actuator/health/readiness HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q '\"UP\"' <&3"]
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
