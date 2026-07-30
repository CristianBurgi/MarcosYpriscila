/**
 * Boda de Marcos y Priscila — Script de Prueba de Carga (Load Test)
 * 
 * Simula:
 * 1. 30 Clientes SSE en paralelo escuchando eventos en tiempo real.
 * 2. 25 Subidas de fotos concurrentes (GET upload-url -> PUT R2 -> POST confirm).
 * 3. Medición de tiempos de respuesta, latencias, tasa de éxito y errores.
 * 
 * Uso:
 *   node scripts/load-test.js [TARGET_URL] [CONCURRENCY]
 * 
 * Ejemplos:
 *   node scripts/load-test.js http://localhost:8080 25
 *   node scripts/load-test.js https://marcosypriscila-production.up.railway.app 25
 */

const http = require('http');
const https = require('https');

const TARGET_URL = process.argv[2] || 'https://marcosypriscila-production.up.railway.app';
const SLUG = 'marcos-y-priscila';
const CONCURRENCY = parseInt(process.argv[3] || '25', 10);

console.log('===============================================================');
console.log('🚀 INICIANDO PRUEBA DE CARGA PREVIA AL EVENTO (FASE 8)');
console.log(`📌 Objetivo: ${TARGET_URL}`);
console.log(`📌 Evento Slug: ${SLUG}`);
console.log(`📌 Concurrencia Simulada: ${CONCURRENCY} usuarios simultáneos`);
console.log('===============================================================\n');

// 1px transparent JPEG byte array para simular foto liviana
const DUMMY_JPEG_BYTES = Buffer.from(
    'R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7',
    'base64'
);

async function runLoadTest() {
    const stats = {
        sseConnectionsOpened: 0,
        sseConnectionsFailed: 0,
        sseEventsReceived: 0,
        uploadUrlSuccess: 0,
        uploadUrlFailed: 0,
        r2PutSuccess: 0,
        r2PutFailed: 0,
        confirmSuccess: 0,
        confirmFailed: 0,
        latencies: []
    };

    console.log(`1️⃣ PASO 1: Estableciendo ${CONCURRENCY} conexiones SSE en paralelo...`);
    const sseClients = [];
    
    for (let i = 1; i <= CONCURRENCY; i++) {
        const client = startSseClient(i, stats);
        sseClients.push(client);
    }

    // Esperar 2 segundos para estabilizar SSE
    await sleep(2000);

    console.log(`\n2️⃣ PASO 2: Disparando ${CONCURRENCY} subidas de fotos simultáneas...`);
    const uploadPromises = [];
    const startTime = Date.now();

    for (let i = 1; i <= CONCURRENCY; i++) {
        uploadPromises.push(simulatePhotoUploadFlow(i, stats));
    }

    const results = await Promise.allSettled(uploadPromises);
    const totalDurationMs = Date.now() - startTime;

    console.log('\n3️⃣ PASO 3: Esperando 3 segundos para confirmar recepción de eventos SSE...');
    await sleep(3000);

    // Cerrar conexiones SSE
    sseClients.forEach(c => c.close());

    printResultsReport(stats, totalDurationMs);
}

function startSseClient(id, stats) {
    const streamUrl = `${TARGET_URL}/api/v1/events/${SLUG}/stream`;
    const isHttps = streamUrl.startsWith('https');
    const clientLib = isHttps ? https : http;

    let req;
    try {
        req = clientLib.get(streamUrl, {
            headers: {
                'Accept': 'text/event-stream',
                'Cache-Control': 'no-cache'
            }
        }, (res) => {
            if (res.statusCode === 200) {
                stats.sseConnectionsOpened++;
                // console.log(`  [Client SSE #${id}] Conectado exitosamente.`);
            } else {
                stats.sseConnectionsFailed++;
                console.error(`  [Client SSE #${id}] Error al conectar. HTTP Status: ${res.statusCode}`);
            }

            res.on('data', (chunk) => {
                const text = chunk.toString();
                if (text.includes('event:') || text.includes('data:')) {
                    stats.sseEventsReceived++;
                }
            });

            res.on('end', () => {
                // Connection closed
            });
        });

        req.on('error', (err) => {
            stats.sseConnectionsFailed++;
            // Suppress unhandled error log if closed manually
        });
    } catch (e) {
        stats.sseConnectionsFailed++;
    }

    return {
        close: () => {
            if (req) req.destroy();
        }
    };
}

async function simulatePhotoUploadFlow(id, stats) {
    const flowStart = Date.now();
    const uploaderName = `Invitado Pruebas #${id}`;
    const filename = `test_photo_${id}_${Date.now()}.jpg`;

    try {
        // A. Pedir Presigned URL
        const uploadUrlRes = await fetchJson(`${TARGET_URL}/api/v1/events/${SLUG}/photos/upload-url`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                filename: filename,
                contentType: 'image/jpeg',
                fileSize: DUMMY_JPEG_BYTES.length
            })
        });

        if (!uploadUrlRes.ok || !uploadUrlRes.data || !uploadUrlRes.data.uploadUrl) {
            stats.uploadUrlFailed++;
            console.error(`  ❌ [Upload #${id}] Fallo al obtener presigned URL (Status: ${uploadUrlRes.status})`);
            return;
        }

        stats.uploadUrlSuccess++;
        const { uploadUrl, storageKey } = uploadUrlRes.data;

        // B. Subir bytes directos (PUT a Presigned URL o Endpoint local)
        const putRes = await putBinary(uploadUrl, DUMMY_JPEG_BYTES, 'image/jpeg');
        if (!putRes.ok) {
            stats.r2PutFailed++;
            console.error(`  ❌ [Upload #${id}] Fallo subida PUT a almacenamiento (Status: ${putRes.status})`);
            return;
        }
        stats.r2PutSuccess++;

        // C. Confirmar subida en BD
        const confirmRes = await fetchJson(`${TARGET_URL}/api/v1/events/${SLUG}/photos/confirm`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                storageKey: storageKey,
                uploaderName: uploaderName,
                caption: `Subida concurrente simulada de prueba #${id}`
            })
        });

        if (!confirmRes.ok) {
            stats.confirmFailed++;
            console.error(`  ❌ [Upload #${id}] Fallo al confirmar foto (Status: ${confirmRes.status})`);
            return;
        }

        stats.confirmSuccess++;
        const duration = Date.now() - flowStart;
        stats.latencies.push(duration);
        console.log(`  ✅ [Upload #${id}] Subida completa en ${duration}ms (Key: ${storageKey.split('/').pop()})`);

    } catch (err) {
        console.error(`  ❌ [Upload #${id}] Excepción durante la subida:`, err.message);
    }
}

async function fetchJson(url, options = {}) {
    const isHttps = url.startsWith('https');
    const clientLib = isHttps ? https : http;

    return new Promise((resolve) => {
        const req = clientLib.request(url, options, (res) => {
            let body = '';
            res.on('data', chunk => body += chunk);
            res.on('end', () => {
                let data = null;
                try { data = JSON.parse(body); } catch (e) {}
                resolve({ ok: res.statusCode >= 200 && res.statusCode < 300, status: res.statusCode, data });
            });
        });
        req.on('error', (err) => resolve({ ok: false, status: 0, error: err }));
        if (options.body) req.write(options.body);
        req.end();
    });
}

async function putBinary(url, buffer, contentType) {
    const isHttps = url.startsWith('https');
    const clientLib = isHttps ? https : http;

    return new Promise((resolve) => {
        const req = clientLib.request(url, {
            method: 'PUT',
            headers: {
                'Content-Type': contentType,
                'Content-Length': buffer.length
            }
        }, (res) => {
            res.on('data', () => {});
            res.on('end', () => resolve({ ok: res.statusCode >= 200 && res.statusCode < 300, status: res.statusCode }));
        });
        req.on('error', (err) => resolve({ ok: false, status: 0, error: err }));
        req.write(buffer);
        req.end();
    });
}

function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

function printResultsReport(stats, totalDurationMs) {
    console.log('\n===============================================================');
    console.log('📊 INFORME DE RESULTADOS DE LA PRUEBA DE CARGA');
    console.log('===============================================================');
    console.log(`⏱️  Duración Total del Test: ${(totalDurationMs / 1000).toFixed(2)}s`);
    console.log(`📡 Conexiones SSE Abiertas: ${stats.sseConnectionsOpened} / ${CONCURRENCY} (${stats.sseConnectionsFailed} fallidas)`);
    console.log(`🔔 Eventos SSE Recibidos: ${stats.sseEventsReceived}`);
    console.log(`🔑 Presigned URLs Generadas: ${stats.uploadUrlSuccess} / ${CONCURRENCY}`);
    console.log(`☁️  PUT a Almacenamiento Exitosos: ${stats.r2PutSuccess} / ${CONCURRENCY}`);
    console.log(`✅ Fotos Confirmadas en BD: ${stats.confirmSuccess} / ${CONCURRENCY}`);

    if (stats.latencies.length > 0) {
        const sum = stats.latencies.reduce((a, b) => a + b, 0);
        const avg = (sum / stats.latencies.length).toFixed(0);
        const min = Math.min(...stats.latencies);
        const max = Math.max(...stats.latencies);
        const sorted = [...stats.latencies].sort((a, b) => a - b);
        const p95 = sorted[Math.floor(sorted.length * 0.95)] || max;

        console.log('\n📈 TIEMPOS DE RESPUESTA (FLUJO COMPLETO DE SUBIDA):');
        console.log(`   - Mínimo: ${min}ms`);
        console.log(`   - Promedio: ${avg}ms`);
        console.log(`   - Percentil 95 (P95): ${p95}ms`);
        console.log(`   - Máximo: ${max}ms`);
    }

    console.log('\n🎯 CONCLUSIÓN & ESTADO DE ACEPTACIÓN:');
    if (stats.confirmSuccess >= CONCURRENCY * 0.9 && stats.sseConnectionsOpened >= CONCURRENCY * 0.9) {
        console.log('🟢 PRUEBA EXITOSA: El sistema soportó la ráfaga de cargas simultáneas y streaming SSE sin fallas críticas.');
    } else {
        console.log('🔴 REVISIÓN REQUERIDA: Hubo fallos en subidas o conexiones SSE. Revisar recursos de servidor/red.');
    }
    console.log('===============================================================\n');
}

runLoadTest();
