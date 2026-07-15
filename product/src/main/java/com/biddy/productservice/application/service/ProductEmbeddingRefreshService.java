package com.biddy.productservice.application.service;

import com.biddy.productservice.domain.model.Product;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
public class ProductEmbeddingRefreshService {

    private final ProductEmbeddingService productEmbeddingService;

    public void refreshAfterCommit(Product product) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    productEmbeddingService.refresh(product);
                }
            });
            return;
        }
        productEmbeddingService.refresh(product);
    }
}
