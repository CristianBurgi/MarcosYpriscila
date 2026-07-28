# -----------------------------------------------------------------------------
# ETAPA 1: Compilación del proyecto Java con Maven (Build Stage)
# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app

# Copiar archivos de configuración y código fuente de Maven
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .

COPY src src

# Compilar la aplicación y empaquetar el archivo JAR sin ejecutar tests
RUN ./mvnw clean package -DskipTests

# -----------------------------------------------------------------------------
# ETAPA 2: Entorno de ejecución de producción (Runtime Stage)
# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy

# Instalar la herramienta nativa libheif-examples para conversión de fotos HEIC a JPEG
RUN apt-get update && \
    apt-get install -y --no-install-recommends libheif-examples && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copiar únicamente el ejecutable .jar generado en la Etapa 1
COPY --from=build /app/target/*.jar app.jar

# Exponer el puerto predeterminado 8080
EXPOSE 8080

# Comando para ejecutar la aplicación Spring Boot
ENTRYPOINT ["java", "-jar", "app.jar"]
