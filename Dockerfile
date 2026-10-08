# -----------------------------------------------------------------------------
# ETAPA 1: Compilación del proyecto Java con Maven (Build Stage)
# -----------------------------------------------------------------------------
FROM maven:3.9.8-eclipse-temurin-21 AS build
WORKDIR /app

# Copiar la definición de dependencias pom.xml y el código fuente
COPY pom.xml .
COPY src src

# Compilar el proyecto y generar el archivo ejecutable .jar
RUN mvn clean package -DskipTests

# -----------------------------------------------------------------------------
# ETAPA 2: Entorno de ejecución de producción (Runtime Stage)
# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy

# Instalar librería nativa libheif-examples para conversión de formatos HEIC/HEIF
RUN apt-get update && \
    apt-get install -y --no-install-recommends libheif-examples && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Usuario sin privilegios: dueño de /app (por si algo escribe en el directorio de trabajo)
RUN useradd --system --uid 1001 --no-create-home eventfoto && chown eventfoto:eventfoto /app

# Copiar únicamente el ejecutable .jar generado en la etapa de compilación
COPY --from=build --chown=eventfoto:eventfoto /app/target/*.jar app.jar

USER eventfoto

# La imagen es la de producción: sin STORAGE_MODE caería en 'local' y saltearía ProductionSecretsValidator.
# Una variable de Railway con el mismo nombre la pisa.
ENV STORAGE_MODE=r2

# Exponer el puerto predeterminado 8080
EXPOSE 8080

# Comando de ejecución de Spring Boot
# El heap toma hasta el 75% de la memoria del contenedor, no el 25% por defecto ni más que el límite.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
