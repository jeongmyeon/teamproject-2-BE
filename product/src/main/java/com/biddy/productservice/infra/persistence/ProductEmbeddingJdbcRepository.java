package com.biddy.productservice.infra.persistence;

import com.biddy.productservice.domain.model.Product;
import com.biddy.productservice.domain.repository.ProductEmbeddingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class ProductEmbeddingJdbcRepository implements ProductEmbeddingRepository {

    private final ProductJpaRepository productJpaRepository;

    @Override
    @Transactional
    public void upsert(Long productId, String embeddingText, List<Double> embedding, String model, int dimensions) {
        productJpaRepository.upsertEmbedding(productId, embeddingText, toVectorLiteral(embedding), model, dimensions);
    }

    @Override
    public List<Product> search(List<Double> queryEmbedding, int limit) {
        return productJpaRepository.searchByEmbedding(toVectorLiteral(queryEmbedding), limit);
    }

    private String toVectorLiteral(List<Double> embedding) {
        return embedding.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(",", "[", "]"));
    }
}
