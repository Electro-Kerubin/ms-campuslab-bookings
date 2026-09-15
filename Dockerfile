# Imagen del microservicio de reservas
# Build:  docker build -t ms-campuslab-bookings:latest .
# Run:    docker run -p 8081:8081 ms-campuslab-bookings:latest

# 1) Compilamos con Maven y JDK 21
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
# Descarga dependencias primero para aprovechar la caché de capas
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests -B

# 2) Imagen final liviana: solo el JRE y el jar
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

# Variables que se inyectan al correr el contenedor (docker run -e ...)
ENV SERVER_PORT=8081

EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
