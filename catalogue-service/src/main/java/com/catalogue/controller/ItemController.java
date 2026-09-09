package com.catalogue.controller;

import com.catalogue.model.Item;
import com.catalogue.repository.ItemRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;

/**
 * REST controller exposing the Catalogue Service APIs.
 *
 * Endpoints:
 *   GET  /catalogue/items          — list all items
 *   GET  /catalogue/items/{id}     — get single item (404 if not found)
 *   PUT  /catalogue/items/{id}/reduce?quantity=N
 *                                  — reduce stock atomically
 *                                    (400 if insufficient, 404 if not found)
 */
@RestController
@RequestMapping("/catalogue/items")
public class ItemController {

    private final ItemRepository itemRepository;

    public ItemController(ItemRepository itemRepository) {
        this.itemRepository = itemRepository;
    }

    // ── GET /catalogue/items ────────────────────────────────────────────────
    @GetMapping
    public ResponseEntity<Collection<Item>> getAllItems() {
        return ResponseEntity.ok(itemRepository.findAll());
    }

    // ── GET /catalogue/items/{id} ───────────────────────────────────────────
    @GetMapping("/{id}")
    public ResponseEntity<Item> getItem(@PathVariable String id) {
        return itemRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ── PUT /catalogue/items/{id}/reduce?quantity=N ─────────────────────────
    @PutMapping("/{id}/reduce")
    public ResponseEntity<String> reduceStock(
            @PathVariable String id,
            @RequestParam int quantity) {

        // Check existence first so we can distinguish 404 vs 400
        if (itemRepository.findById(id).isEmpty()) {
            return ResponseEntity.status(404).body("item not found");
        }

        boolean success = itemRepository.reduceStock(id, quantity);
        if (success) {
            return ResponseEntity.ok("stock reduced");
        } else {
            return ResponseEntity.badRequest().body("number of items exceeded");
        }
    }
}
