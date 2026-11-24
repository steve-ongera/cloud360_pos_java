package com.cloud360.pos.model;

public record ShopSettings(
        String name,
        String address,
        String phone,
        String email,
        String kraPin,
        String currency,
        String receiptFooter) {
}