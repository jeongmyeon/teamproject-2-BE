package com.biddy.searchservice.infrastructure.client.product;

import com.biddy.searchservice.domain.exception.ProductSearchUnavailableException;
import com.biddy.searchservice.presentation.dto.ProductSearchResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

@Component
public class ProductClient {

    private final RestClient restClient;

    public ProductClient(
            RestClient.Builder restClientBuilder,
            @Value("${biddy.client.product-service-url:http://localhost:8082}") String productServiceUrl
    ) {
        this.restClient = restClientBuilder
                .baseUrl(productServiceUrl)
                .build();
    }

    public List<ProductSearchResult> searchByEmbedding(List<Double> queryEmbedding, int size) {
        ProductVectorSearchRequest request = new ProductVectorSearchRequest(queryEmbedding, size);

        try {
            return restClient.post()
                    .uri("/api/products/search/vector")
                    .body(request)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
        } catch (RestClientException e) {
            throw new ProductSearchUnavailableException(
                    "Product Service vector search endpoint is not available yet.",
                    e
            );
        }
    }
}
