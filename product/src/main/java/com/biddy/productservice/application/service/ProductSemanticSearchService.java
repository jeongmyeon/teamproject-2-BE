package com.biddy.productservice.application.service;

import com.biddy.productservice.domain.model.Product;
import com.biddy.productservice.domain.repository.ProductEmbeddingRepository;
import com.biddy.productservice.infra.embedding.TextEmbeddingClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductSemanticSearchService {

    private final TextEmbeddingClient textEmbeddingClient;
    private final ProductEmbeddingRepository productEmbeddingRepository;

    public List<Product> search(String query, int limit) {
        if (!StringUtils.hasText(query)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "검색어를 입력해주세요.");
        }

        int safeLimit = Math.min(Math.max(limit, 1), 50);
        List<Double> queryEmbedding = textEmbeddingClient.embed(query.trim());
        return productEmbeddingRepository.search(queryEmbedding, safeLimit);
    }
}
