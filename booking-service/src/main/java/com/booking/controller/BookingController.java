package com.booking.controller;

import com.booking.dto.BookingRequest;
import com.booking.dto.CatalogueItem;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

/**
 * REST controller for the Booking Service.
 *
 * Endpoint:
 *   POST /booking
 *   Body: { "itemId": "ITEM001", "quantity": 2 }
 *
 * Business outcomes:
 *   "item not found"            — item ID does not exist in the catalogue
 *   "number of items exceeded"  — requested quantity > available stock
 *   "item booked"               — booking succeeded; stock has been reduced
 */
@RestController
@RequestMapping("/booking")
public class BookingController {

    private final RestTemplate restTemplate;
    private final String catalogueServiceUrl;

    public BookingController(RestTemplate restTemplate, String catalogueServiceUrl) {
        this.restTemplate = restTemplate;
        this.catalogueServiceUrl = catalogueServiceUrl;
    }

    @PostMapping
    public ResponseEntity<String> book(@RequestBody BookingRequest request) {

        String itemId   = request.getItemId();
        int    quantity = request.getQuantity();

        // ── Step 1: Validate input ─────────────────────────────────────────
        if (itemId == null || itemId.isBlank()) {
            return ResponseEntity.badRequest().body("itemId must not be blank");
        }
        if (quantity <= 0) {
            return ResponseEntity.badRequest().body("quantity must be greater than zero");
        }

        // ── Step 2: Check item existence in Catalogue Service ──────────────
        CatalogueItem item;
        try {
            String url = catalogueServiceUrl + "/catalogue/items/" + itemId;
            item = restTemplate.getForObject(url, CatalogueItem.class);
        } catch (HttpClientErrorException.NotFound ex) {
            return ResponseEntity.status(404).body("item not found");
        } catch (Exception ex) {
            return ResponseEntity.status(503)
                    .body("catalogue service unavailable: " + ex.getMessage());
        }

        if (item == null) {
            return ResponseEntity.status(404).body("item not found");
        }

        // ── Step 3: Check available stock ──────────────────────────────────
        if (quantity > item.getAvailableCount()) {
            return ResponseEntity.badRequest().body("number of items exceeded");
        }

        // ── Step 4: Reduce stock in Catalogue Service ──────────────────────
        try {
            String reduceUrl = catalogueServiceUrl
                    + "/catalogue/items/" + itemId + "/reduce?quantity=" + quantity;
            ResponseEntity<String> reduceResponse =
                    restTemplate.exchange(reduceUrl,
                            org.springframework.http.HttpMethod.PUT,
                            null,
                            String.class);

            if (reduceResponse.getStatusCode().is2xxSuccessful()) {
                return ResponseEntity.ok("item booked");
            } else {
                // Catalogue returned 400 — another thread may have reduced stock
                return ResponseEntity.badRequest().body("number of items exceeded");
            }
        } catch (HttpClientErrorException.BadRequest ex) {
            // Catalogue returned 400 (concurrent booking exhausted stock)
            return ResponseEntity.badRequest().body("number of items exceeded");
        } catch (HttpClientErrorException.NotFound ex) {
            return ResponseEntity.status(404).body("item not found");
        } catch (Exception ex) {
            return ResponseEntity.status(503)
                    .body("catalogue service unavailable: " + ex.getMessage());
        }
    }
}
