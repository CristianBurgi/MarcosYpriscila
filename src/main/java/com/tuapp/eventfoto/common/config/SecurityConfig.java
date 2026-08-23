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

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

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
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(customAuthenticationEntryPoint())
            )
            .authorizeHttpRequests(auth -> auth
                // Rutas públicas de vistas y recursos estáticos
                .requestMatchers(
                    "/", "/index.html", "/album.html", "/pantalla.html", "/screen.html", "/upload.html", "/menu.html", "/messages.html",
                    "/css/**", "/js/**", "/images/**", "/uploads/**"
                ).permitAll()

                // Actuator: únicamente GET /actuator/health es público (requerido por Railway)
                .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()

                // Almacenamiento local (subida PUT y lectura GET explícitas para desarrollo)
                .requestMatchers(HttpMethod.GET, "/api/v1/storage/files", "/api/v1/storage/files/**").permitAll()
                .requestMatchers(HttpMethod.PUT, "/api/v1/storage/local-upload").permitAll()
                
                // Rutas públicas de la API REST para invitados
                .requestMatchers(
                    "/api/v1/events/**", "/api/v1/photos/**", "/api/v1/messages/**", "/api/v1/comments/**"
                ).permitAll()
                
                // Rutas de Login y Autenticación Admin
                .requestMatchers("/admin/login", "/api/v1/admin/auth/login").permitAll()
                
                // Rutas protegidas que requieren rol ADMIN
                .requestMatchers("/admin/**", "/api/v1/admin/**").hasRole("ADMIN")
                
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
            } else {
                response.sendRedirect("/admin/login");
            }
        };
    }
}

