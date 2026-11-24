package com.cloud360.pos.model;

import java.time.LocalDateTime;

public record SaleAmendment(
        long id,
        String amendedByName,
        String reason,
        LocalDateTime createdAt,
        String beforeJson,
        String afterJson) {
}