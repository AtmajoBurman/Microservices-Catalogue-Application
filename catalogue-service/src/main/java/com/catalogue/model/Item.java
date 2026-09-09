package com.catalogue.model;

/**
 * Represents a catalogue item with its inventory details.
 */
public class Item {

    private String itemId;
    private String name;
    private double price;
    private int availableCount;

    public Item() {}

    public Item(String itemId, String name, double price, int availableCount) {
        this.itemId = itemId;
        this.name = name;
        this.price = price;
        this.availableCount = availableCount;
    }

    // --- Getters and Setters ---

    public String getItemId() {
        return itemId;
    }

    public void setItemId(String itemId) {
        this.itemId = itemId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public double getPrice() {
        return price;
    }

    public void setPrice(double price) {
        this.price = price;
    }

    public int getAvailableCount() {
        return availableCount;
    }

    public void setAvailableCount(int availableCount) {
        this.availableCount = availableCount;
    }

    @Override
    public String toString() {
        return "Item{itemId='" + itemId + "', name='" + name + "', price=" + price
               + ", availableCount=" + availableCount + "}";
    }
}
