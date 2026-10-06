// Paleta de un evento derivada de UN color: copia en JS de EventPalette.java (la fuente de verdad), para la
// vista previa en vivo del wizard. Mismos arreglos que en Java: gris (saturación < 5) sin saturación en los
// derivados, y primaryDeep nunca más claro que el primario. EventPaletteParityTest corre este archivo en Node.
(function (root) {
    function hexToHsl(hex) {
        const r = parseInt(hex.slice(1, 3), 16) / 255;
        const g = parseInt(hex.slice(3, 5), 16) / 255;
        const b = parseInt(hex.slice(5, 7), 16) / 255;
        const max = Math.max(r, g, b), min = Math.min(r, g, b);
        let h = 0, s = 0;
        const l = (max + min) / 2;
        if (max !== min) {
            const d = max - min;
            s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
            switch (max) {
                case r: h = (g - b) / d + (g < b ? 6 : 0); break;
                case g: h = (b - r) / d + 2; break;
                default: h = (r - g) / d + 4; break;
            }
            h /= 6;
        }
        return { h: h * 360, s: s * 100, l: l * 100 };
    }
    function clamp(v, mn, mx) { return Math.max(mn, Math.min(mx, v)); }
    function hslToHex(h, s, l) {
        h = ((h % 360) + 360) % 360;
        s = clamp(s, 0, 100) / 100;
        l = clamp(l, 0, 100) / 100;
        const c = (1 - Math.abs(2 * l - 1)) * s;
        const x = c * (1 - Math.abs((h / 60) % 2 - 1));
        const m = l - c / 2;
        let r = 0, g = 0, b = 0;
        if (h < 60) { r = c; g = x; b = 0; }
        else if (h < 120) { r = x; g = c; b = 0; }
        else if (h < 180) { r = 0; g = c; b = x; }
        else if (h < 240) { r = 0; g = x; b = c; }
        else if (h < 300) { r = x; g = 0; b = c; }
        else { r = c; g = 0; b = x; }
        const toHex = (v) => Math.round((v + m) * 255).toString(16).padStart(2, '0');
        return '#' + toHex(r) + toHex(g) + toHex(b);
    }
    function readableText(hex) {
        return hexToHsl(hex).l > 55 ? '#1C2321' : '#FFFFFF';
    }
    function derivePalette(color) {
        const primary = color.toLowerCase();
        const c = hexToHsl(primary);
        const gray = c.s < 5;
        const secondary = hslToHex(c.h, gray ? 0 : Math.max(c.s * 0.55, 15), clamp(c.l + 14, 25, 75));
        return {
            primary,
            primaryDeep: hslToHex(c.h, gray ? 0 : c.s, Math.min(clamp(c.l - 20, 10, 90), c.l)),
            primaryLight: hslToHex(c.h, gray ? 0 : Math.max(c.s * 0.35, 10), 92),
            secondary,
            textOnPrimary: readableText(primary),
            textOnSecondary: readableText(secondary),
        };
    }
    root.derivePalette = derivePalette;
    if (typeof module !== 'undefined') module.exports = { derivePalette };
})(typeof window !== 'undefined' ? window : globalThis);
