package com.tuapp.eventfoto.common.email;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.templateresolver.ITemplateResolver;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/**
 * Arma un mail con sus dos versiones: templates/email/{nombre}.html y templates/email-text/{nombre}.txt (modo TEXT de
 * Thymeleaf, sin escapado HTML), con las mismas variables. Los clientes que no muestran HTML usan la de texto.
 */
@Configuration
@RequiredArgsConstructor
public class EmailTemplates {

    private final ITemplateEngine templateEngine;

    public EmailService.EmailMessage render(String to, String subject, String template, Map<String, ?> variables,
                                            String reference) {
        Context context = new Context();
        variables.forEach(context::setVariable);
        return new EmailService.EmailMessage(to, subject,
                templateEngine.process("email/" + template, context),
                templateEngine.process("email-text/" + template, context),
                reference);
    }

    /** Solo resuelve email-text/*, antes que el resolver HTML de Spring Boot (que si no reclamaría el nombre). */
    @Bean
    public static ITemplateResolver emailTextTemplateResolver() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".txt");
        resolver.setTemplateMode(TemplateMode.TEXT);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resolver.setResolvablePatterns(Set.of("email-text/*"));
        resolver.setCheckExistence(true);
        resolver.setOrder(0);
        return resolver;
    }
}
