package com.cloud360.pos.model;

import java.math.BigDecimal;
import java.util.List;

/** Everything the POS screen sends to SaleDao.create(). amountPaid null = paid in full. */
public record SaleRequest(
        String customerName,
        String customerPhone,
        BigDecimal discount,
        String paymentMethod,
        String referenceNumber,
        BigDecimal amountPaid,
        List<CartLine> lines) {
}