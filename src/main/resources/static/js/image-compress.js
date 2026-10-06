// Lectura y reducción de imágenes en el navegador, compartida por la subida de invitados (upload.html) y la imagen
// de fondo del wizard. Sacado de upload.html tal cual (Fase 9.5): el arreglo de Android (PR #29) depende de que
// la foto se lea ENTERA a memoria apenas se elige (copyFileToMemory) y que todo lo demás trabaje sobre esa copia,
// nunca sobre el File del selector (algunos navegadores de celular lo invalidan después).

const TYPE_BY_EXTENSION = { jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', webp: 'image/webp', heic: 'image/heic', heif: 'image/heif' };
// Tipo de la foto: el declarado por el navegador; si viene vacío (pasa con algunos selectores de galería), se
// deduce de la extensión. SUPUESTO: sin extensión conocida se asume image/jpeg. No abre nada: el servidor valida
// el contenido real por la firma binaria de los bytes, no por este tipo.
function inferImageType(file) {
    if (file.type) return file.type;
    const match = /\.([A-Za-z0-9]+)$/.exec(file.name || '');
    return (match && TYPE_BY_EXTENSION[match[1].toLowerCase()]) || 'image/jpeg';
}

// Lee la foto entera a memoria y devuelve un File que ya no depende del selector.
async function copyFileToMemory(file) {
    const buffer = await file.arrayBuffer();
    return new File([buffer], file.name || 'photo', { type: inferImageType(file), lastModified: file.lastModified || Date.now() });
}

// Recodifica a JPEG (calidad 0.85) con el lado mayor en maxDim, si pesa más de minBytes. Re-codificar además le
// saca el EXIF (GPS). Si el navegador no puede decodificarla (HEIC en Android), devuelve `file` tal cual: la COPIA
// EN MEMORIA que recibe esta función, nunca el original de la galería; el servidor la convierte.
async function compressImageIfNeeded(file, { maxDim = 2048, minBytes = 1.5 * 1024 * 1024, log = () => {} } = {}) {
    if (!file || file.size <= minBytes) {
        return file;
    }

    return new Promise((resolve) => {
        const img = new Image();
        const url = URL.createObjectURL(file);
        img.onload = () => {
            URL.revokeObjectURL(url);

            let width = img.width;
            let height = img.height;

            if (width > maxDim || height > maxDim) {
                if (width > height) {
                    height = Math.round((height * maxDim) / width);
                    width = maxDim;
                } else {
                    width = Math.round((width * maxDim) / height);
                    height = maxDim;
                }
            }

            const canvas = document.createElement('canvas');
            canvas.width = width;
            canvas.height = height;

            const ctx = canvas.getContext('2d');
            ctx.drawImage(img, 0, 0, width, height);

            canvas.toBlob((blob) => {
                if (blob) {
                    // El blob es JPEG: el nombre lleva extensión .jpg (si no, "captura.png" viajaría como
                    // image/jpeg y el servidor la rechazaría por incoherente).
                    const baseName = (file.name || 'photo').replace(/\.[^./\\]*$/, '') || 'photo';
                    const compressedFile = new File([blob], baseName + '.jpg', {
                        type: 'image/jpeg',
                        lastModified: Date.now()
                    });
                    resolve(compressedFile);
                } else {
                    resolve(file);
                }
            }, 'image/jpeg', 0.85);
        };
        img.onerror = () => {
            URL.revokeObjectURL(url);
            log('comprimir: no se pudo decodificar la imagen -> se sube la copia en memoria sin comprimir');
            resolve(file);
        };
        img.src = url;
    });
}
