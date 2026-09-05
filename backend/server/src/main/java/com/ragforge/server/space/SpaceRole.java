package com.ragforge.server.space;

import com.ragforge.server.common.ApiException;
import org.springframework.http.HttpStatus;

public enum SpaceRole {
    SPACE_ADMIN,
    EDITOR,
    VIEWER;

    public static SpaceRole parse(String value) {
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_space_role", "Invalid space role",
                    "Role must be SPACE_ADMIN, EDITOR, or VIEWER");
        }
    }
}
