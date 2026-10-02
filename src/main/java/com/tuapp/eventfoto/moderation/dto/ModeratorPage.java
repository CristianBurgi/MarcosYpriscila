package com.tuapp.eventfoto.moderation.dto;

import java.util.List;

public record ModeratorPage<T>(List<T> items, boolean hasNext) {
}
