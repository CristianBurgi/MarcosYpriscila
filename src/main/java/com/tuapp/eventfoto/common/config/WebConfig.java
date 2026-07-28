package com.tuapp.eventfoto.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * Mapea las rutas /uploads/** y /photos/** hacia la carpeta local de archivos del disco ('uploads/').
     * De este modo, cualquier foto subida localmente se sirve directamente por Tomcat sin errores 404/500.
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path uploadsPath = Paths.get("uploads").toAbsolutePath().normalize();
        String uploadsUrl = uploadsPath.toUri().toString();
        if (!uploadsUrl.endsWith("/")) {
            uploadsUrl += "/";
        }

        registry.addResourceHandler("/uploads/**", "/photos/**")
                .addResourceLocations(uploadsUrl);
    }
}
