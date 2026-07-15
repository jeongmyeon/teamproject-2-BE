package com.biddy.productservice.infra.persistence;

import com.biddy.productservice.domain.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import com.biddy.productservice.domain.model.SaleType;
import org.springframework.data.repository.query.Param;

import java.util.List;


public interface ProductJpaRepository extends JpaRepository<Product, Long> {
    List<Product> findBySellerId(Long sellerId);

    List<Product> findBySaleType(SaleType saleType);

    @Query("SELECT p FROM Product p WHERE p.status NOT IN ('SOLD_OUT', 'HIDDEN')")
    List<Product> findAllVisible();

    @Query("SELECT p FROM Product p WHERE p.saleType = :saleType AND p.status NOT IN ('SOLD_OUT', 'HIDDEN')")
    List<Product> findBySaleTypeVisible(@Param("saleType") SaleType saleType);

    @Modifying
    @Query(value = """
            INSERT INTO product_embedding (product_id, embedding_text, embedding, model, dimensions, updated_at)
            VALUES (:productId, :embeddingText, CAST(:embedding AS vector), :model, :dimensions, CURRENT_TIMESTAMP)
            ON CONFLICT (product_id)
            DO UPDATE SET embedding_text = EXCLUDED.embedding_text,
                          embedding = EXCLUDED.embedding,
                          model = EXCLUDED.model,
                          dimensions = EXCLUDED.dimensions,
                          updated_at = CURRENT_TIMESTAMP
            """, nativeQuery = true)
    void upsertEmbedding(@Param("productId") Long productId,
                         @Param("embeddingText") String embeddingText,
                         @Param("embedding") String embedding,
                         @Param("model") String model,
                         @Param("dimensions") int dimensions);

    @Query(value = """
            SELECT p.*
            FROM "product" p
            JOIN product_embedding pe ON pe.product_id = p.id
            WHERE p.status NOT IN ('SOLD_OUT', 'HIDDEN')
            ORDER BY pe.embedding <=> CAST(:embedding AS vector)
            LIMIT :limit
            """, nativeQuery = true)
    List<Product> searchByEmbedding(@Param("embedding") String embedding,
                                    @Param("limit") int limit);
}
