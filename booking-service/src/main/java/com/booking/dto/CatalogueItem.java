package com.booking.dto;

/**
 * Item details returned by the Catalogue Service.
 * Only the fields we care about are mapped here.
 */
public class CatalogueItem {

    private String itemId;
    private String name;
    private double price;
    private int availableCount;

    public CatalogueItem() {}

    public String getItemId() { return itemId; }
    public void setItemId(String itemId) { this.itemId = itemId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public double getPrice() { return price; }
    public void setPrice(double price) { this.price = price; }

    public int getAvailableCount() { return availableCount; }
    public void setAvailableCount(int availableCount) { this.availableCount = availableCount; }
}
