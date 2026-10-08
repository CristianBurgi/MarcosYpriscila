package com.tuapp.eventfoto.common.config;

import com.tuapp.eventfoto.event.EventAccessInterceptor;
import com.tuapp.eventfoto.event.PublicEventExpiryInterceptor;
import com.tuapp.eventfoto.event.OwnedEventArgumentResolver;
import lombok.RequiredArgsConstructor;
import com.tuapp.eventfoto.demo.DemoEnabledInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final OwnedEventArgumentResolver ownedEventArgumentResolver;
    private final EventAccessInterceptor eventAccessInterceptor;
    private final PublicEventExpiryInterceptor publicEventExpiryInterceptor;
    private final DemoEnabledInterceptor demoEnabledInterceptor;

    /**
     * Todo el panel del organizador y todo el lado del moderador (/moderar/{token}, /api/v1/moderate/{token}/...)
     * pasan por la resolución y autorización del evento. Las únicas rutas que quedan afuera están en
     * AdminRouteExceptions (con su motivo).
     * /superadmin/** tiene su propia cadena de seguridad y no se toca acá.
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(eventAccessInterceptor)
                .addPathPatterns("/admin/**", "/api/v1/admin/**", "/moderar/**", "/api/v1/moderate/**");
        // Lado público: un álbum vencido no se sirve (ver PublicEventExpiryInterceptor).
        registry.addInterceptor(publicEventExpiryInterceptor).addPathPatterns(PublicEventExpiryInterceptor.PATHS);
        // Demo (Fase 9.7-B): el interruptor app.demo.enabled. La demo no pasa por los interceptores de eventos.
        registry.addInterceptor(demoEnabledInterceptor).addPathPatterns(DemoEnabledInterceptor.PATHS);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(ownedEventArgumentResolver);
    }

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
