FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build

COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src ./src
COPY installer ./installer
RUN mvn package -DskipTests -q

FROM docker:27-cli AS docker-cli

FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

RUN groupadd --system deployops \
    && useradd --system --create-home --home-dir /home/deployops --gid deployops deployops \
    && install -d -o deployops -g deployops -m 0700 /home/deployops/.deployops /home/deployops/.deployops/nginx-uploads

COPY --from=builder /build/target/*.jar app.jar
COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
RUN chown deployops:deployops app.jar

USER deployops

EXPOSE 9090

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
ENV NGINX_UPLOAD_DIR="/home/deployops/.deployops/nginx-uploads"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
