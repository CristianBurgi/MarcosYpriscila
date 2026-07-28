package com.tuapp.eventfoto.storage;

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

    /**
     * Convierte una imagen HEIC en bytes a formato JPEG en bytes mediante heif-convert.
     *
     * @param heicBytes Imagen original HEIC en bytes
     * @return Imagen convertida en formato JPEG
     */
    byte[] convertHeicToJpeg(byte[] heicBytes);
}
