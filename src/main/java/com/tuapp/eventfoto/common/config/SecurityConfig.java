package com.tuapp.eventfoto.common.config;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.web.header.Header;
import org.springframework.security.web.header.writers.CacheControlHeadersWriter;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    /** Rutas cuya URL o respuesta contienen la credencial del moderador. */
    private static final RequestMatcher MODERATOR_SENSITIVE = new OrRequestMatcher(
        new AntPathRequestMatcher("/moderar/**"),
        new AntPathRequestMatcher("/api/v1/moderate/**"),
        new AntPathRequestMatcher("/api/v1/admin/events/*/moderator-link/**"),
        // /compra/retorno: la URL trae el external_reference de la compra (fase 9.3)
        new AntPathRequestMatcher("/compra/**"),
        // Confirmación y estado de la compra (fase 9.4): ni cache ni referrer
        new AntPathRequestMatcher("/api/v1/checkout/confirm"),
        new AntPathRequestMatcher("/api/v1/checkout/status"));

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Define explícitamente un UserDetailsService vacío para que Spring Boot NO
     * active su auto-configuración por defecto (UserDetailsServiceAutoConfiguration),
     * que genera un usuario "user" con contraseña aleatoria en cada arranque y la
     * imprime en el log ("Using generated security password: ...").
     *
     * La app no usa el AuthenticationManager/UserDetailsService de Spring Security
     * para nada: la autenticación de admin es 100% propia (ver AdminAuthService,
     * que valida contra security.admin.email/password y emite un JWT), y el
     * filtro es STATELESS. Este bean es solo para silenciar el warning; no
     * habilita ningún login adicional porque no tiene usuarios cargados.
     */
    @Bean
    public UserDetailsService userDetailsService() {
        return new InMemoryUserDetailsManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            // El slug del evento y el token de moderador van en la URL. A otros sitios, por defecto, solo se les
            // manda el origen; en el lado del moderador y en los endpoints del panel que entregan o regeneran el link
            // ni eso (no-referrer) y la respuesta no se cachea (no-store), tampoco la de los errores.
            .headers(headers -> headers
                .cacheControl(HeadersConfigurer.CacheControlConfig::disable)
                .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(MODERATOR_SENSITIVE,
                    new StaticHeadersWriter(List.of(
                        new Header("Cache-Control", "no-store"),
                        new Header("Referrer-Policy", "no-referrer")))))
                .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(new NegatedRequestMatcher(MODERATOR_SENSITIVE),
                    new CacheControlHeadersWriter()))
                .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(new NegatedRequestMatcher(MODERATOR_SENSITIVE),
                    new ReferrerPolicyHeaderWriter(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(customAuthenticationEntryPoint())
            )
            .authorizeHttpRequests(auth -> auth
                // Rutas públicas de vistas y recursos estáticos
                .requestMatchers(
                    "/", "/e/**", "/favicon.ico",
                    "/album.html", "/screen.html", "/upload.html", "/menu.html", "/messages.html", // redirects 302 a /e/{slug}
                    "/css/**", "/js/**", "/images/**", "/uploads/**"
                ).permitAll()

                // Actuator: únicamente GET /actuator/health es público (requerido por Railway)
                .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()

                // Almacenamiento local (subida PUT y lectura GET explícitas para desarrollo)
                .requestMatchers(HttpMethod.GET, "/api/v1/storage/files", "/api/v1/storage/files/**").permitAll()
                .requestMatchers(HttpMethod.PUT, "/api/v1/storage/local-upload").permitAll()
                
                // Rutas públicas de la API REST para invitados
                // (los comentarios cuelgan de /events/{slug}/photos/{photoId}/comments; no hay rutas
                // públicas fuera de /events/**, así que una ruta nueva sin slug nace cerrada)
                .requestMatchers("/api/v1/events/**").permitAll()
                
                // Lado del moderador: la credencial es el token de la URL; la verifica EventAccessInterceptor (ámbito
                // MODERATOR) en cada request y responde 404 si no resuelve a un evento. Nunca un rol: no abre /admin/**.
                .requestMatchers("/moderar/**", "/api/v1/moderate/**").permitAll()

                // Rutas de Login y Autenticación del organizador
                .requestMatchers("/admin/login", "/api/v1/admin/auth/login").permitAll()

                // Activación de cuenta del organizador (link de un solo uso, público hasta que se consume)
                .requestMatchers(HttpMethod.GET, "/activar-cuenta").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/organizer/activate").permitAll()

                // Checkout (fase 9.3), rutas exactas. Solo existen con app.checkout.enabled=true; apagado, no hay handler y dan 404.
                // /api/v1/admin/checkout NO va acá: es del organizador y cae en /api/v1/admin/** (hasRole ORGANIZER).
                .requestMatchers(HttpMethod.GET, "/comprar", "/compra/retorno").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/checkout").permitAll()
                // Fase 9.4: webhook de MP (lo autentica la firma x-signature, no una sesión) y los dos endpoints de la
                // página de retorno (sin sesión ni cookies: la referencia ya no da acceso a nada). Rutas exactas.
                .requestMatchers(HttpMethod.POST, "/api/v1/checkout/webhook", "/api/v1/checkout/confirm", "/api/v1/checkout/status").permitAll()

                // Rutas protegidas que requieren rol ORGANIZER
                .requestMatchers("/admin/**", "/api/v1/admin/**").hasRole("ORGANIZER")

                // Login del superadmin
                .requestMatchers("/superadmin/login", "/api/v1/superadmin/auth/login").permitAll()

                // Rutas protegidas que requieren rol SUPERADMIN, aisladas de ORGANIZER
                .requestMatchers("/superadmin/**", "/api/v1/superadmin/**").hasRole("SUPERADMIN")

                // Cierre por defecto: cualquier otra request requiere autenticación
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public AuthenticationEntryPoint customAuthenticationEntryPoint() {
        return (request, response, authException) -> {
            String requestURI = request.getRequestURI();
            if (requestURI.startsWith("/api/")) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Acceso no autorizado: Token JWT ausente o inválido");
            } else if (requestURI.startsWith("/superadmin")) {
                response.sendRedirect("/superadmin/login");
            } else {
                response.sendRedirect("/admin/login");
            }
        };
    }
}

