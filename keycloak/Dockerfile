# Build Keycloak with the face authenticator JAR (Keycloak 23 Quarkus).
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /w
COPY keycloak-deepface-provider/pom.xml keycloak-deepface-provider/
COPY keycloak-deepface-provider/src keycloak-deepface-provider/src/
RUN mvn -q -f keycloak-deepface-provider/pom.xml -DskipTests package

FROM quay.io/keycloak/keycloak:23.0
USER root
COPY --from=build /w/keycloak-deepface-provider/target/keycloak-deepface-provider-1.0.0.jar /opt/keycloak/providers/
RUN /opt/keycloak/bin/kc.sh build
USER 1000
