package com.tuapp.eventfoto.testsupport;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Captura TODO lo que se loguea mientras está abierta (mensaje formateado + excepción con su stack trace), con el
 * código de la app en DEBUG, para poder afirmar que un valor sensible (el token de moderador) no aparece en ningún lado.
 * Uso: {@code try (LogCapture logs = LogCapture.start()) { ...; assertThat(logs.text()).doesNotContain(token); }}.
 */
public final class LogCapture implements AutoCloseable {

    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private final Logger app = (Logger) LoggerFactory.getLogger("com.tuapp.eventfoto");
    private final Level previousAppLevel = app.getLevel();
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private LogCapture() {
        appender.start();
        root.addAppender(appender);
        app.setLevel(Level.DEBUG);
    }

    public static LogCapture start() {
        return new LogCapture();
    }

    public List<ILoggingEvent> events() {
        return List.copyOf(appender.list);
    }

    /** Todo el texto logueado: mensaje formateado y, si hay, la excepción completa. */
    public String text() {
        return appender.list.stream()
                .map(event -> event.getLevel() + " " + event.getLoggerName() + " " + event.getFormattedMessage()
                        + (event.getThrowableProxy() != null ? "\n" + ThrowableProxyUtil.asString(event.getThrowableProxy()) : ""))
                .collect(Collectors.joining("\n"));
    }

    @Override
    public void close() {
        root.detachAppender(appender);
        app.setLevel(previousAppLevel);
        appender.stop();
    }
}
