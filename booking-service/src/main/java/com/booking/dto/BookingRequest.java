package com.booking.dto;

/**
 * Incoming booking request payload.
 * Example JSON: { "itemId": "ITEM001", "quantity": 2 }
 */
public class BookingRequest {

    private String itemId;
    private int quantity;

    public BookingRequest() {}

    public BookingRequest(String itemId, int quantity) {
        this.itemId = itemId;
        this.quantity = quantity;
    }

    public String getItemId() {
        return itemId;
    }

    public void setItemId(String itemId) {
        this.itemId = itemId;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }
}
