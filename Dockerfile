# syntax=docker/dockerfile:1

FROM eclipse-temurin:17-jdk-jammy

WORKDIR /app

#COPY .mvn/ .mvn
#COPY mvnw pom.xml ./
#RUN ./mvnw dependency:resolve

COPY src ./src
COPY gradlew gradlew
COPY gradle ./gradle
COPY settings.gradle.kts settings.gradle.kts
COPY build.gradle.kts build.gradle.kts

# executed in docker image build
RUN ["./gradlew", "shadowJar"]

# executed in docker image run
CMD java -Dvertx.logger-delegate-factory-class-name=io.vertx.core.logging.SLF4JLogDelegateFactory -jar ./build/libs/moquette-collector-all.jar
