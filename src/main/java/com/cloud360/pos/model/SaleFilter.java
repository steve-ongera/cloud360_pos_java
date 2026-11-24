package com.cloud360.pos.model;

import java.time.LocalDate;

/** Search filters for the Sales History screen. Null / false means "no filter". */
public record SaleFilter(
        LocalDate from,
        LocalDate to,
        String method,
        Long servedBy,
        boolean unpaidOnly,
        String search,
        Long onlyUserId) { // set for cashiers: they only ever see their own sales
}