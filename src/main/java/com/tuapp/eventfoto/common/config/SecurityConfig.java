package com.tuapp.eventfoto.common.config;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
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
                    "/css/**", "/js/**", "/images/**", "/actuator/**", "/uploads/**"
                ).permitAll()
                
                // Rutas públicas de la API REST
                .requestMatchers(
                    "/api/public/**", "/api/v1/events/**", "/api/v1/photos/**", "/api/v1/messages/**", "/api/v1/comments/**",
                    "/api/events/**", "/api/photos/**", "/api/messages/**", "/api/comments/**", "/api/sse/**", "/api/storage/**"
                ).permitAll()
                
                // Rutas de Login y Autenticación Admin
                .requestMatchers("/admin/login", "/api/v1/admin/auth/login").permitAll()
                
                // Rutas protegidas que requieren rol ADMIN
                .requestMatchers("/admin/**", "/api/v1/admin/**").hasRole("ADMIN")
                
                .anyRequest().permitAll()
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
