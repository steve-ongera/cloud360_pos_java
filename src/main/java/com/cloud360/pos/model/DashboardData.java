package com.cloud360.pos.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Everything the Dashboard screen shows, loaded in one go by DashboardDao. */
public record DashboardData(
        BigDecimal todayRevenue,
        int todaySales,
        long itemsSold,
        BigDecimal outstanding,
        int lowStockCount,
        int outOfStockCount,
        boolean tracksExpiry,
        int expiryAlertDays,
        int expiringCount,
        List<DayPoint> trend,
        List<MethodTotal> methods,
        List<TopProduct> topProducts,
        List<RecentSale> recentSales,
        List<LowStockItem> lowStock) {

    public record DayPoint(LocalDate date, BigDecimal revenue, int sales) {}

    public record MethodTotal(String method, BigDecimal total, int count) {}

    public record TopProduct(String name, String unit, long quantity, BigDecimal revenue) {}

    public record RecentSale(String saleNumber, LocalDateTime soldAt, String customer,
                             BigDecimal total, BigDecimal paid, String method) {}

    public record LowStockItem(String name, String unit, int stock, int reorderLevel) {}
}