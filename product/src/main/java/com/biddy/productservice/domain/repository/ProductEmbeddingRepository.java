package com.biddy.productservice.domain.repository;

import com.biddy.productservice.domain.model.Product;

import java.util.List;

public interface ProductEmbeddingRepository {

    void upsert(Long productId, String embeddingText, List<Double> embedding, String model, int dimensions);

    List<Product> search(List<Double> queryEmbedding, int limit);
}
