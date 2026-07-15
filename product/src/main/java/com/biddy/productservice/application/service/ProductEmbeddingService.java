package com.biddy.productservice.application.service;

import com.biddy.productservice.domain.model.Product;
import com.biddy.productservice.domain.repository.ProductEmbeddingRepository;
import com.biddy.productservice.infra.embedding.TextEmbeddingClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductEmbeddingService {

    private final ProductEmbeddingTextBuilder textBuilder;
    private final TextEmbeddingClient textEmbeddingClient;
    private final ProductEmbeddingRepository productEmbeddingRepository;

    @Async
    public void refresh(Product product) {
        try {
            String embeddingText = textBuilder.build(product);
            if (embeddingText.isBlank()) {
                log.warn("상품 임베딩 생성을 건너뜁니다. productId={}, reason=empty_text", product.getId());
                return;
            }

            List<Double> embedding = textEmbeddingClient.embed(embeddingText);
            productEmbeddingRepository.upsert(product.getId(), embeddingText, embedding,
                    textEmbeddingClient.model(), textEmbeddingClient.dimensions());
        } catch (Exception exception) {
            log.warn("상품 임베딩 갱신 실패. productId={}, message={}", product.getId(), exception.getMessage());
        }
    }
}
