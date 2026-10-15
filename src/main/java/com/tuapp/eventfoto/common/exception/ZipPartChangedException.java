package com.tuapp.eventfoto.common.exception;

/**
 * Una foto de corte del ZIP por partes ya no está en el álbum (se borró después de abrir el panel) o no es de este
 * evento. Se responde 409 en vez de recalcular: recargando el panel, las partes se arman de nuevo sin perder fotos.
 */
public class ZipPartChangedException extends RuntimeException {

    public ZipPartChangedException() {
        super("El álbum cambió desde que abriste el panel. Recargá la página y volvé a descargar las partes.");
    }
}
