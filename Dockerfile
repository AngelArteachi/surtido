# One image definition for every Spring Boot module: docker build --build-arg MODULE=services/orders .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY libs libs
COPY platform platform
COPY services services
ARG MODULE
# `extra_ca` is optional: it lets the build download dependencies behind a
# TLS-inspecting proxy or antivirus (see docker-compose.override.example.yml).
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    --mount=type=secret,id=extra_ca,required=false \
    if [ -s /run/secrets/extra_ca ]; then \
      keytool -importcert -noprompt -cacerts -storepass changeit -alias extra-ca -file /run/secrets/extra_ca >/dev/null; \
    fi && \
    mvn -q -B -pl "${MODULE}" -am -DskipTests package && \
    cp "${MODULE}"/target/*.jar /app.jar

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
COPY --from=build /app.jar /app/app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
