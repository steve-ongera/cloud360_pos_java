package com.cloud360.pos.model;

import java.math.BigDecimal;

public class Product {
    private long id;
    private String name = "";
    private String genericName = "";
    private String category = "";
    private String unit = "piece";
    private BigDecimal unitPrice = BigDecimal.ZERO;
    private int reorderLevel = 20;
    private String barcode = "";
    private String sku = "";
    private boolean visible = true;
    private int stock; // sum of batch quantities, filled by ProductDao

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getGenericName() { return genericName; }
    public void setGenericName(String genericName) { this.genericName = genericName; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }

    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }

    public int getReorderLevel() { return reorderLevel; }
    public void setReorderLevel(int reorderLevel) { this.reorderLevel = reorderLevel; }

    public String getBarcode() { return barcode; }
    public void setBarcode(String barcode) { this.barcode = barcode; }

    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }

    public boolean isVisible() { return visible; }
    public void setVisible(boolean visible) { this.visible = visible; }

    public int getStock() { return stock; }
    public void setStock(int stock) { this.stock = stock; }

    public boolean isLowStock() { return stock <= reorderLevel; }

    /** OUT, LOW or OK. */
    public String getStatus() {
        if (stock == 0) return "OUT";
        return stock <= reorderLevel ? "LOW" : "OK";
    }

    @Override
    public String toString() { return name; }
}