package com.biddy.productservice.application.service;

import com.biddy.productservice.domain.model.Product;
import com.biddy.productservice.domain.repository.ProductEmbeddingRepository;
import com.biddy.productservice.presentation.dto.ProductVectorSearchResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductVectorSearchService {

    private final ProductEmbeddingRepository productEmbeddingRepository;

    public List<ProductVectorSearchResponse> search(List<Double> queryEmbedding, int size) {
        int safeSize = Math.min(Math.max(size, 1), 50);
        return productEmbeddingRepository.search(queryEmbedding, safeSize)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private ProductVectorSearchResponse toResponse(Product product) {
        return new ProductVectorSearchResponse(
                product.getId(),
                product.getName(),
                product.getPrice(),
                product.getStatus(),
                product.getSellerId(),
                product.getStock(),
                null
        );
    }
}
