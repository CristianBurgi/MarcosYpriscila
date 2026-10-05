package com.tuapp.eventfoto.checkout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuapp.eventfoto.testsupport.PostgresTestCredentials;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Ejecuta de verdad el JS de /compra/retorno (la página tal como la sirve la app) en Node, con el DOM, fetch,
 * sessionStorage e history simulados, y mira qué pantalla queda visible y qué endpoints llama. Node viene en los
 * runners de GitHub; en local se omite si no está instalado, en CI sin Node FALLA.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReturnPageScriptTest {

    private static final String REF = "07364afb-1111-4222-8333-444455556666";

    /** Stubs mínimos del navegador. setTimeout inmediato: las 30 rondas corren en milisegundos. */
    private static final String HARNESS = """
            const vm = require('vm');
            const scenario = JSON.parse(require('fs').readFileSync(process.argv[2], 'utf8'));
            const script = require('fs').readFileSync(process.argv[3], 'utf8');
            const els = {};
            function el(id) {
                if (!els[id]) {
                    const classes = new Set();
                    els[id] = { id, style: {}, textContent: '', href: '', disabled: false, listeners: {},
                        classList: { add: c => classes.add(c), remove: c => classes.delete(c), has: c => classes.has(c) },
                        addEventListener(type, fn) { this.listeners[type] = fn; } };
                }
                return els[id];
            }
            scenario.stateIds.forEach(id => el(id));
            el('state-none').classList.add('visible');
            const visible = () => scenario.stateIds.filter(id => el(id).classList.has('visible'));
            const storage = new Map();
            if (scenario.saved) storage.set('eventfoto.checkoutReturn', JSON.stringify(scenario.saved));
            const calls = [];
            const timeline = [];
            let statusIndex = 0;
            Object.assign(globalThis, {
                window: { location: { search: scenario.search, pathname: '/compra/retorno' } },
                document: { getElementById: el, querySelectorAll: () => scenario.stateIds.map(el) },
                history: { replaceState() { globalThis.window.location.search = ''; } },
                sessionStorage: { getItem: k => storage.has(k) ? storage.get(k) : null, setItem: (k, v) => storage.set(k, v) },
                setTimeout: fn => setImmediate(fn),
                fetch: async (url, options) => {
                    calls.push({ url, body: JSON.parse(options.body) });
                    timeline.push(visible().join(','));
                    if (url.endsWith('/confirm')) return { ok: true, status: 204, json: async () => ({}) };
                    const status = scenario.statuses[Math.min(statusIndex++, scenario.statuses.length - 1)];
                    return { ok: true, status: 200, json: async () => status };
                }
            });
            vm.runInThisContext(script);
            const initial = visible().join(',');
            process.on('beforeExit', () => {
                const out = { initial, final: visible().join(','), timeline, calls,
                    incompleteHref: el('incompleteRetryLink').href, eventName: el('eventName').textContent,
                    search: globalThis.window.location.search, saved: storage.get('eventfoto.checkoutReturn') || null };
                process.stdout.write(JSON.stringify(out));
                process.exit(0);
            });
            """;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private static String node;
    private String script;
    private List<String> stateIds;

    @BeforeAll
    static void findNode() {
        for (String candidate : List.of("node", "node.exe")) {
            try {
                Process p = new ProcessBuilder(candidate, "--version").redirectErrorStream(true).start();
                if (p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    node = candidate;
                    return;
                }
            } catch (IOException | InterruptedException ignored) {
                // probar el siguiente
            }
        }
        if (PostgresTestCredentials.isCi()) {
            fail("CI=true pero no hay Node: este test ejecuta el JS de la página de retorno y no puede saltearse en CI.");
        }
        assumeTrue(false, "Sin Node instalado: se omite (solo en local)");
    }

    @BeforeEach
    void loadPage() throws Exception {
        String html = mockMvc.perform(get("/compra/retorno")).andReturn().getResponse().getContentAsString();
        int start = html.lastIndexOf("<script>");
        int end = html.lastIndexOf("</script>");
        script = html.substring(start + "<script>".length(), end);
        stateIds = new ArrayList<>();
        Matcher m = Pattern.compile("class=\"state[^\"]*\" id=\"([^\"]+)\"").matcher(html);
        while (m.find()) {
            stateIds.add(m.group(1));
        }
        assertThat(stateIds).contains("state-none", "state-incomplete", "state-pending", "state-created");
    }

    private static Map<String, Object> status(String state, boolean repurchase) {
        return Map.of("state", state, "eventName", "Mi evento", "repurchase", repurchase);
    }

    private JsonNode run(String search, Map<String, Object> saved, List<Map<String, Object>> statuses) throws Exception {
        Path dir = Files.createTempDirectory("retorno-js");
        Path harness = dir.resolve("harness.js");
        Path scenario = dir.resolve("scenario.json");
        Path page = dir.resolve("page.js");
        Files.writeString(harness, HARNESS, StandardCharsets.UTF_8);
        Files.writeString(page, script, StandardCharsets.UTF_8);
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("search", search);
        data.put("saved", saved);
        data.put("statuses", statuses);
        data.put("stateIds", stateIds);
        Files.writeString(scenario, objectMapper.writeValueAsString(data), StandardCharsets.UTF_8);

        Process process = new ProcessBuilder(node, harness.toString(), scenario.toString(), page.toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("node terminó").isTrue();
        assertThat(process.exitValue()).as("node sin errores: %s", output).isZero();
        return objectMapper.readTree(output);
    }

    private static List<String> urls(JsonNode result) {
        List<String> urls = new ArrayList<>();
        result.get("calls").forEach(call -> urls.add(call.get("url").asText()));
        return urls;
    }

    @Test
    @DisplayName("Referencia con payment_id=\"null\": 'No completaste el pago' (nunca 'confirmando'), sin confirm, y sigue consultando el estado")
    void nullPaymentIdShowsIncompleteAndPollsInTheBackground() throws Exception {
        JsonNode result = run("?external_reference=" + REF + "&payment_id=null&status=null", null, List.of(status("PENDING", false)));

        assertThat(result.get("initial").asText()).isEqualTo("state-incomplete");
        assertThat(result.get("final").asText()).as("se queda en 'No completaste el pago', no pasa a 'Todavía no tenemos la confirmación'")
                .isEqualTo("state-incomplete");
        assertThat(result.get("timeline")).allSatisfy(state -> assertThat(state.asText()).isEqualTo("state-incomplete"));
        assertThat(urls(result)).isNotEmpty().allMatch(url -> url.equals("/api/v1/checkout/status"));
        assertThat(result.get("calls").get(0).get("body").get("externalReference").asText()).isEqualTo(REF);
        assertThat(result.get("incompleteHref").asText()).isEqualTo("/comprar");
        assertThat(result.get("search").asText()).as("la URL queda limpia").isEmpty();
    }

    @Test
    @DisplayName("Sin payment_id en la URL: el mismo estado 'No completaste el pago'")
    void missingPaymentIdShowsIncomplete() throws Exception {
        JsonNode result = run("?external_reference=" + REF, null, List.of(status("PENDING", false)));
        assertThat(result.get("initial").asText()).isEqualTo("state-incomplete");
        assertThat(urls(result)).doesNotContain("/api/v1/checkout/confirm");
    }

    @Test
    @DisplayName("Recompra: 'Intentar de nuevo' lleva a Mis eventos (lo dice la consulta de estado)")
    void repurchaseRetryGoesToMyEvents() throws Exception {
        JsonNode result = run("?external_reference=" + REF + "&payment_id=null", null, List.of(status("PENDING", true)));
        assertThat(result.get("final").asText()).isEqualTo("state-incomplete");
        assertThat(result.get("incompleteHref").asText()).isEqualTo("/admin/eventos");
    }

    @Test
    @DisplayName("Si el pago llega por otro lado mientras muestra 'No completaste el pago', pasa a '¡Listo!'")
    void paymentArrivingElsewhereSwitchesToCreated() throws Exception {
        JsonNode result = run("?external_reference=" + REF + "&payment_id=null", null,
                List.of(status("PENDING", false), status("PENDING", false), status("CREATED", false)));
        assertThat(result.get("initial").asText()).isEqualTo("state-incomplete");
        assertThat(result.get("final").asText()).isEqualTo("state-created");
        assertThat(result.get("eventName").asText()).isEqualTo("Mi evento");
        assertThat(result.get("calls")).as("deja de consultar al llegar a un estado final").hasSize(3);
    }

    @Test
    @DisplayName("Con un payment_id válido: 'Estamos confirmando…', confirma y consulta; a los 90 s, 'Volver a consultar'")
    void validPaymentIdConfirmsAndPolls() throws Exception {
        JsonNode result = run("?external_reference=" + REF + "&payment_id=181339591265&status=approved", null, List.of(status("PENDING", false)));
        assertThat(result.get("initial").asText()).isEqualTo("state-pending");
        assertThat(urls(result)).contains("/api/v1/checkout/confirm", "/api/v1/checkout/status");
        assertThat(result.get("calls").get(0).get("body").get("paymentId").asText()).isEqualTo("181339591265");
        assertThat(result.get("final").asText()).isEqualTo("state-slow");
    }

    @Test
    @DisplayName("Recarga: sin parámetros en la URL usa lo guardado en sessionStorage (con y sin payment_id)")
    void reloadUsesSessionStorage() throws Exception {
        Map<String, Object> savedWithoutPayment = new java.util.HashMap<>();
        savedWithoutPayment.put("paymentId", null);
        savedWithoutPayment.put("reference", REF);
        JsonNode incomplete = run("", savedWithoutPayment, List.of(status("PENDING", false)));
        assertThat(incomplete.get("initial").asText()).isEqualTo("state-incomplete");

        JsonNode pending = run("", Map.of("paymentId", "181339591265", "reference", REF), List.of(status("PENDING", false)));
        assertThat(pending.get("initial").asText()).isEqualTo("state-pending");

        JsonNode fresh = run("?external_reference=" + REF + "&payment_id=null", null, List.of(status("PENDING", false)));
        assertThat(fresh.get("saved").asText()).as("guarda la referencia antes de limpiar la URL").contains(REF);
    }

    @Test
    @DisplayName("Sin referencia (ni en la URL ni guardada): estado neutro y ninguna consulta")
    void noReferenceShowsNeutralState() throws Exception {
        JsonNode result = run("", null, List.of(status("PENDING", false)));
        assertThat(result.get("initial").asText()).isEqualTo("state-none");
        assertThat(result.get("final").asText()).isEqualTo("state-none");
        assertThat(result.get("calls")).isEmpty();
    }
}
