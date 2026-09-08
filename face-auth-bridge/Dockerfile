FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S spring && adduser -S spring -G spring && apk add --no-cache wget
USER spring:spring
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8071
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD wget --no-verbose -t1 -O- http://127.0.0.1:8071/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
