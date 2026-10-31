package mx.surtido.catalog;

import java.util.UUID;

public record Product(
        UUID id,
        String sku,
        String name,
        String category,
        String pack,
        long priceCents,
        int stock,
        int lowStockThreshold,
        boolean active) {

    public boolean lowStock() {
        return stock <= lowStockThreshold;
    }
}
