package com.tuapp.eventfoto.storage;

import org.springframework.core.io.InputStreamSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.OptionalLong;
import java.util.UUID;

public interface StorageService {

    /**
     * Genera una Presigned URL con expiración corta (10 minutos) para subir una foto directamente a Cloudflare R2.
     * Valida server-side que el contentType sea únicamente image/jpeg, image/png, image/webp o image/heic.
     *
     * @param key Clave u objeto en el bucket (ej: photos/uuid.jpg)
     * @param contentType Tipo MIME del archivo a subir
     * @return URL prefirmada HTTPS lista para PUT desde el cliente
     */
    String generateUploadUrl(String key, String contentType);

    /**
     * Genera una Presigned GET URL de descarga directa con expiración corta (10 minutos) para un objeto en R2.
     *
     * @param key Clave del objeto en el bucket
     * @return URL prefirmada HTTPS de lectura directa
     */
    String generateDownloadUrl(String key);

    /**
     * Genera la URL pública de acceso a un archivo almacenado en Cloudflare R2.
     *
     * @param key Clave del objeto en el bucket
     * @return URL pública completa de lectura
     */
    String generatePublicUrl(String key);

    /**
     * Sube directamente una secuencia de bytes (usado para fotos HEIC convertidas a JPEG).
     *
     * @param key Clave del objeto en el bucket
     * @param bytes Contenido en bytes de la imagen
     * @param contentType Tipo MIME de la imagen (ej: image/jpeg)
     */
    void uploadBytes(String key, byte[] bytes, String contentType);

    /** Igual, con el header Cache-Control del objeto (null = sin header). */
    void uploadBytes(String key, byte[] bytes, String contentType, String cacheControl);

    /**
     * Sube un archivo del disco sin cargarlo en memoria (el JPEG que deja una conversión HEIC).
     */
    void uploadFile(String key, Path file, String contentType);

    /**
     * Sube por stream, sin cargar el contenido en memoria. {@code source} se abre de nuevo en cada intento del SDK (un
     * MultipartFile se puede reabrir), así que un PUT reintentado no queda con un stream ya consumido.
     */
    void uploadStream(String key, InputStreamSource source, long size, String contentType);

    /**
     * Convierte una imagen HEIC en bytes a formato JPEG en bytes mediante heif-convert.
     *
     * @param heicBytes Imagen original HEIC en bytes
     * @return Imagen convertida en formato JPEG
     */
    byte[] convertHeicToJpeg(byte[] heicBytes);

    /**
     * Igual, por stream: la foto va directo a un archivo temporal y no pasa entera por el heap.
     *
     * @return el JPEG en un archivo temporal; quien llama lo borra
     * @throws IOException si falla la lectura de {@code heic}
     */
    Path convertHeicToJpeg(InputStream heic) throws IOException;

    /**
     * Tamaño en bytes del objeto, SIN leerlo (HeadObject en R2, tamaño del archivo en modo local).
     *
     * @param key Clave del objeto
     * @return el tamaño, o vacío si el objeto no existe
     */
    OptionalLong objectSize(String key);

    /**
     * Elimina de forma permanente un objeto del almacenamiento (Cloudflare R2 o disco local).
     *
     * @param key Clave del objeto a eliminar
     */
    void deleteFile(String key);

    /**
     * Borra TODO lo que hay bajo {@code events/{eventId}/}: fotos, fondo y huérfanos (PUT presignado sin /confirm).
     * Recibe el id y no un prefijo libre: no hay forma de pedirle que borre otra cosa (ni el bucket entero).
     * Repetirlo no falla: sin objetos devuelve 0.
     *
     * @return cantidad de objetos borrados
     * @throws com.tuapp.eventfoto.common.exception.StorageException si algún objeto no se pudo borrar o el listado
     *         no se vacía; en ese caso quien llama no toca la base y reintenta después
     */
    int deleteEventObjects(UUID eventId);

    /**
     * Fase 9.7-B: borra lo de UNA demo ({@code demo/{sid}/}) o, con {@code sid} null, todo {@code demo/} ("Vaciar
     * demo"). Mismas garantías que {@link #deleteEventObjects}; nunca toca {@code events/}.
     */
    int deleteDemoObjects(String sid);

    /**
     * Abre y devuelve un InputStream hacia el contenido del objeto almacenado en R2 o disco local.
     *
     * @param key Clave del objeto a transmitir
     * @return InputStream del objeto
     */
    InputStream streamObject(String key);
}
