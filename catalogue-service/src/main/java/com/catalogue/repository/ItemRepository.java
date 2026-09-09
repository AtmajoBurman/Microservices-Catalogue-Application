package com.catalogue.repository;

import com.catalogue.model.Item;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory item repository pre-loaded with exactly three catalogue items.
 * ConcurrentHashMap ensures thread-safe reads; inventory reduction uses
 * a synchronized block on the individual item for atomicity.
 */
@Repository
public class ItemRepository {

    private final Map<String, Item> store = new ConcurrentHashMap<>();

    public ItemRepository() {
        // ── Three initial catalogue items ──────────────────────────────────
        store.put("ITEM001", new Item("ITEM001", "Wireless Headphones", 2999.99, 10));
        store.put("ITEM002", new Item("ITEM002", "Mechanical Keyboard",  4599.00,  5));
        store.put("ITEM003", new Item("ITEM003", "USB-C Hub",             899.50, 20));
        // ──────────────────────────────────────────────────────────────────
    }

    /** Returns all items in the catalogue. */
    public Collection<Item> findAll() {
        return store.values();
    }

    /** Returns the item with the given ID, or empty if not found. */
    public Optional<Item> findById(String itemId) {
        return Optional.ofNullable(store.get(itemId));
    }

    /**
     * Atomically reduces the available count of an item by {@code quantity}.
     *
     * @param itemId   the item to update
     * @param quantity the number of units to deduct
     * @return {@code true} if the reduction succeeded,
     *         {@code false} if the item does not exist or stock is insufficient
     */
    public boolean reduceStock(String itemId, int quantity) {
        Item item = store.get(itemId);
        if (item == null) {
            return false;
        }
        // Synchronize on the item object to make the check-then-act atomic
        synchronized (item) {
            if (item.getAvailableCount() < quantity) {
                return false;
            }
            item.setAvailableCount(item.getAvailableCount() - quantity);
            return true;
        }
    }
}
