package com.biddy.productservice.infra.llm;

import com.biddy.productservice.domain.model.Product;

import java.util.List;

public interface ProductRecommendationClient {

    List<Long> selectRecommendedProductIds(String query, List<Product> products, int recommendationLimit);
}
