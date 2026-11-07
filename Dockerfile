FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:25-jre
COPY --from=build /src/target/loomkv.jar /opt/loomkv/loomkv.jar
RUN mkdir /data && chown 1000:1000 /data
USER 1000
WORKDIR /data
EXPOSE 6379
ENTRYPOINT ["java", "-XX:+UseZGC", "-jar", "/opt/loomkv/loomkv.jar", "--bind", "0.0.0.0", "--port", "6379"]
CMD ["--aof", "/data/appendonly.aof"]
