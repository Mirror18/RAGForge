package com.ragforge.server.ingestion;

import java.util.List;

/** A bounded forward page. The cursor is opaque to API clients. */
public record CursorPage<T>(List<T> items, String nextCursor) {
    public CursorPage {
        items = List.copyOf(items == null ? List.of() : items);
    }
}
