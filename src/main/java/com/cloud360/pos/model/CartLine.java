package com.cloud360.pos.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** One line in the cashier's cart. */
public class CartLine {

    private final Product product;
    private int quantity;
    private BigDecimal lineAmount; // cashier-entered total for the line, null = price x quantity

    public CartLine(Product product, int quantity) {
        this.product = product;
        this.quantity = quantity;
    }

    public Product getProduct() { return product; }

    public int getQuantity() { return quantity; }

    /** Changing the quantity clears any custom line amount. */
    public void setQuantity(int quantity) {
        this.quantity = quantity;
        this.lineAmount = null;
    }

    public BigDecimal getLineAmount() { return lineAmount; }

    public void setLineAmount(BigDecimal lineAmount) { this.lineAmount = lineAmount; }

    public BigDecimal getLineTotal() {
        if (lineAmount != null) {
            return lineAmount;
        }
        return product.getUnitPrice().multiply(BigDecimal.valueOf(quantity));
    }

    public BigDecimal getUnitPrice() {
        if (quantity <= 0) {
            return product.getUnitPrice();
        }
        return getLineTotal().divide(BigDecimal.valueOf(quantity), 2, RoundingMode.HALF_UP);
    }
}