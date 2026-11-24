package com.cloud360.pos.model;

import java.time.LocalDate;

public class ProductBatch {
    private long id;
    private long productId;
    private String batchNumber;
    private int quantityReceived;
    private int quantityRemaining;
    private LocalDate expiryDate; // null when the shop does not track expiry
    private LocalDate receivedDate;

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public long getProductId() { return productId; }
    public void setProductId(long productId) { this.productId = productId; }

    public String getBatchNumber() { return batchNumber; }
    public void setBatchNumber(String batchNumber) { this.batchNumber = batchNumber; }

    public int getQuantityReceived() { return quantityReceived; }
    public void setQuantityReceived(int quantityReceived) { this.quantityReceived = quantityReceived; }

    public int getQuantityRemaining() { return quantityRemaining; }
    public void setQuantityRemaining(int quantityRemaining) { this.quantityRemaining = quantityRemaining; }

    public LocalDate getExpiryDate() { return expiryDate; }
    public void setExpiryDate(LocalDate expiryDate) { this.expiryDate = expiryDate; }

    public LocalDate getReceivedDate() { return receivedDate; }
    public void setReceivedDate(LocalDate receivedDate) { this.receivedDate = receivedDate; }
}