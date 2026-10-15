package com.tuapp.eventfoto.common.email;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

/**
 * Corre el trabajo de los mails fuera del hilo del request (el executor que arma Spring Boot): quien pide no espera a
 * Resend y el tiempo de respuesta no depende de si el mail sale. Con app.mail.async=false (tests) corre en el mismo
 * hilo, así un envío de un test no cae en las verificaciones del siguiente.
 */
@Component
public class MailExecutor {

    private final Executor delegate;

    public MailExecutor(@Qualifier("applicationTaskExecutor") TaskExecutor applicationTaskExecutor,
                        @Value("${app.mail.async:true}") boolean async) {
        this.delegate = async ? applicationTaskExecutor : Runnable::run;
    }

    public void execute(Runnable task) {
        delegate.execute(task);
    }
}
