package com.biddy.searchservice.infrastructure.client.product;

import java.util.List;

public record ProductVectorSearchRequest(
        List<Double> queryEmbedding,
        int size
) {
}
