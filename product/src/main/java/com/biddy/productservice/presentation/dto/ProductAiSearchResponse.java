package com.biddy.productservice.presentation.dto;

import com.biddy.productservice.domain.model.Product;

import java.util.List;

public record ProductAiSearchResponse(
        List<Product> recommendedProducts,
        List<Product> products
) {
}
