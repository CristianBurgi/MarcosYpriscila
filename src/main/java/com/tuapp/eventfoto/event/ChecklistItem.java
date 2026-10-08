package com.tuapp.eventfoto.event;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Casillas de la checklist previa del panel (Fase 9.8), guardadas como máscara de bits en events.checklist. El bit
 * de cada una es explícito: reordenar o agregar casillas nunca puede cambiar el significado de lo ya guardado.
 */
public enum ChecklistItem {
    PANTALLA("pantalla", 0),
    TARJETAS("tarjetas", 1),
    MODERADOR("moderador", 2),
    DJ("dj", 3),
    PRUEBA("prueba", 4);

    /** La clave de la ruta (PUT/DELETE .../checklist/{item}) y del panel. */
    public final String key;
    public final int mask;

    ChecklistItem(String key, int bit) {
        this.key = key;
        this.mask = 1 << bit;
    }

    public static Optional<ChecklistItem> fromKey(String key) {
        return Arrays.stream(values()).filter(item -> item.key.equals(key)).findFirst();
    }

    /** Claves marcadas en una máscara, en el orden de la checklist. */
    public static List<String> marked(int checklist) {
        return Arrays.stream(values()).filter(item -> (checklist & item.mask) != 0).map(item -> item.key).toList();
    }
}
