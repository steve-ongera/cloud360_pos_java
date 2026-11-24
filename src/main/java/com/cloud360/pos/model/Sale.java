package com.cloud360.pos.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record Sale(
        long id,
        String saleNumber,
        String customerName,
        String customerPhone,
        BigDecimal subtotal,
        BigDecimal discount,
        BigDecimal totalAmount,
        BigDecimal amountPaid,
        String paymentMethod,
        String referenceNumber,
        String servedByName,
        LocalDateTime soldAt,
        List<SaleItem> items) {

    /** Positive = still owed, negative = change given. */
    public BigDecimal balance() {
        return totalAmount.subtract(amountPaid);
    }
}