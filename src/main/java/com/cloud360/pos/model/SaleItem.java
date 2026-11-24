package com.cloud360.pos.model;

import java.math.BigDecimal;

public record SaleItem(
        long productId,
        String productName,
        String unit,
        int quantity,
        BigDecimal unitPrice,
        BigDecimal subtotal) {
}