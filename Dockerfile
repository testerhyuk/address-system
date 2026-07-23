FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

COPY build/libs/address-system-*.jar /app/address-system.jar

USER 10001:10001

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/address-system.jar"]
