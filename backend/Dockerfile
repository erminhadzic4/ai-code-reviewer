# Two stages: the first builds the jar with the full JDK and Maven, the second runs it on a
# JRE only. The final image holds neither the sources nor Maven.

FROM eclipse-temurin:21.0.12.1_1-jdk-resolute AS build
WORKDIR /build

# Dependencies first: this layer is rebuilt only when pom.xml or the wrapper changes,
# not on every change to the code.
COPY --chmod=755 mvnw ./
COPY .mvn .mvn
COPY pom.xml ./
RUN ./mvnw -B -q dependency:go-offline

# Tests are not run here: they need Docker for their database, and CI runs them.
COPY src/main src/main
RUN ./mvnw -B -q package -Dmaven.test.skip=true && cp target/*.jar app.jar

FROM eclipse-temurin:21.0.12.1_1-jre-resolute
WORKDIR /app
RUN useradd --system --no-create-home app
COPY --from=build /build/app.jar app.jar
USER app
EXPOSE 8080
# Exec form: Java is process 1 and receives the stop signal itself, so the application shuts
# down in order instead of being killed when the stop timeout runs out.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
