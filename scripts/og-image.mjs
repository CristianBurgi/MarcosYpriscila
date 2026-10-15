// Imagen para compartir (1200x630, JPEG) desde la portada de la landing, con la app corriendo en local.
// Necesita playwright-core (npm i playwright-core) y Chrome instalado. Uso:
//   node scripts/og-image.mjs http://localhost:8080/ src/main/resources/static/images/og-eventfoto.jpg
import { chromium } from 'playwright-core';
const [url, out] = process.argv.slice(2);
const browser = await chromium.launch({ channel: 'chrome' });
const page = await browser.newPage({ viewport: { width: 1200, height: 630 }, deviceScaleFactor: 1, reducedMotion: 'reduce' });
await page.goto(url, { waitUntil: 'networkidle' });
await page.evaluate(() => document.fonts.ready);
// La portada sin cabecera ni texto chico: título, bajada y la pantalla del salón.
await page.addStyleTag({ content: 'body{min-height:630px}.nav .login,main>section,footer{display:none!important}.nav{padding-top:36px!important}.hero{padding-top:24px!important;padding-bottom:0!important}.facts,.ctas{display:none!important}.hero h1{font-size:4rem!important}' });
await page.screenshot({ path: out, type: 'jpeg', quality: 85, clip: { x: 0, y: 0, width: 1200, height: 630 } });
await browser.close();
