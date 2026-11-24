package com.cloud360.pos.util;

import java.math.BigDecimal;
import java.text.DecimalFormat;

public final class Fmt {

    private Fmt() {}

    public static String money(BigDecimal v) {
        return "KES " + new DecimalFormat("#,##0.00").format(v == null ? BigDecimal.ZERO : v);
    }
}