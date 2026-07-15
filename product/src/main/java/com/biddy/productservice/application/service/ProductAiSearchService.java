package com.biddy.productservice.application.service;

import com.biddy.productservice.domain.model.Product;
import com.biddy.productservice.infra.llm.ProductRecommendationClient;
import com.biddy.productservice.presentation.dto.ProductAiSearchResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductAiSearchService {

    private final ProductSemanticSearchService productSemanticSearchService;
    private final ProductRecommendationClient productRecommendationClient;

    public ProductAiSearchResponse search(String query, int limit) {
        List<Product> products = productSemanticSearchService.search(query, limit);
        List<Long> recommendedIds = productRecommendationClient.selectRecommendedProductIds(query, products, 2);
        List<Product> recommendedProducts = reorderByRecommendedIds(products, recommendedIds);
        List<Product> sortedProducts = mergeRecommendedFirst(recommendedProducts, products);
        return new ProductAiSearchResponse(recommendedProducts, sortedProducts);
    }

    private List<Product> reorderByRecommendedIds(List<Product> products, List<Long> recommendedIds) {
        Map<Long, Product> productById = products.stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        return recommendedIds.stream()
                .map(productById::get)
                .filter(java.util.Objects::nonNull)
                .limit(2)
                .toList();
    }

    private List<Product> mergeRecommendedFirst(List<Product> recommendedProducts, List<Product> products) {
        LinkedHashSet<Long> recommendedIdSet = recommendedProducts.stream()
                .map(Product::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<Product> sortedProducts = new ArrayList<>(recommendedProducts);
        products.stream()
                .filter(product -> !recommendedIdSet.contains(product.getId()))
                .forEach(sortedProducts::add);
        return sortedProducts;
    }
}
