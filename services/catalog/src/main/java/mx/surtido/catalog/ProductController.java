package mx.surtido.catalog;

import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ProductController {
    private static final String COLUMNS =
            "id, sku, name, category, pack, price_cents, stock, low_stock_threshold, active";

    private final JdbcClient db;

    public ProductController(JdbcClient db) {
        this.db = db;
    }

    private static Product map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Product(
                rs.getObject("id", UUID.class),
                rs.getString("sku"),
                rs.getString("name"),
                rs.getString("category"),
                rs.getString("pack"),
                rs.getLong("price_cents"),
                rs.getInt("stock"),
                rs.getInt("low_stock_threshold"),
                rs.getBoolean("active"));
    }

    public record ProductView(
            UUID id, String sku, String name, String category, String pack, long priceCents, int stock, boolean lowStock) {
        static ProductView of(Product p) {
            return new ProductView(p.id(), p.sku(), p.name(), p.category(), p.pack(), p.priceCents(), p.stock(), p.lowStock());
        }
    }

    @GetMapping("/products")
    public List<ProductView> list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean lowStock) {
        return db.sql("SELECT " + COLUMNS + """
                 FROM products
                WHERE active
                  AND (CAST(:category AS text) IS NULL OR category = :category)
                  AND (CAST(:q AS text) IS NULL OR name ILIKE '%' || :q || '%' OR sku ILIKE '%' || :q || '%')
                  AND (NOT :lowStock OR stock <= low_stock_threshold)
                ORDER BY category, name
                """)
                .param("category", blankToNull(category))
                .param("q", blankToNull(q))
                .param("lowStock", lowStock)
                .query((rs, i) -> ProductView.of(map(rs)))
                .list();
    }

    @GetMapping("/products/{id}")
    public ProductView get(@PathVariable UUID id) {
        return db.sql("SELECT " + COLUMNS + " FROM products WHERE id = ?")
                .param(id)
                .query((rs, i) -> ProductView.of(map(rs)))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
    }

    public record CategoryView(String name, int products) {}

    @GetMapping("/categories")
    public List<CategoryView> categories() {
        return db.sql("SELECT category, count(*) AS n FROM products WHERE active GROUP BY category ORDER BY category")
                .query((rs, i) -> new CategoryView(rs.getString("category"), rs.getInt("n")))
                .list();
    }

    public record Restock(@Min(1) @Max(10_000) int quantity) {}

    /** The warehouse received merchandise. */
    @PostMapping("/products/{id}/restock")
    public ProductView restock(@PathVariable UUID id, @Valid @RequestBody Restock body) {
        int changed = db.sql("UPDATE products SET stock = stock + ?, updated_at = now() WHERE id = ?")
                .params(body.quantity(), id)
                .update();
        if (changed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found");
        return get(id);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
