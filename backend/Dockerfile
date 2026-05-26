FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn package -DskipTests

FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY --from=builder /build/target/*.jar app.jar

EXPOSE 18081

ENV RAG_ENABLED=false
ENV LLM_ENABLED=false
ENV SERVER_PORT=18081

ENTRYPOINT ["java", "-jar", "app.jar"]
