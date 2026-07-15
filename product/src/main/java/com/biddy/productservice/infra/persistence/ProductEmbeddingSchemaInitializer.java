package com.biddy.productservice.infra.persistence;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProductEmbeddingSchemaInitializer {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void initialize() {
        try {
            jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS product_embedding (
                        product_id BIGINT PRIMARY KEY,
                        embedding_text TEXT NOT NULL,
                        embedding VECTOR(1536) NOT NULL,
                        model VARCHAR(100) NOT NULL,
                        dimensions INTEGER NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            jdbcTemplate.execute("""
                    CREATE INDEX IF NOT EXISTS idx_product_embedding_vector
                    ON product_embedding
                    USING hnsw (embedding vector_cosine_ops)
                    """);
        } catch (Exception exception) {
            log.warn("pgvector 스키마 초기화 실패. message={}", exception.getMessage());
        }
    }
}
