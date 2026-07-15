package com.biddy.productservice.application.service;

import com.biddy.productservice.application.usecase.ProductCommandUseCase;
import com.biddy.productservice.domain.event.ProductRegisteredForAuctionEvent;
import com.biddy.productservice.domain.model.Product;
import com.biddy.productservice.domain.model.SaleType;
import com.biddy.productservice.domain.repository.ProductRepository;
import com.biddy.productservice.infra.event.ProductEventProducer;
import com.biddy.productservice.presentation.dto.ProductCreateRequest;
import com.biddy.productservice.presentation.dto.ProductUpdateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Transactional
public class ProductCommandService implements ProductCommandUseCase {

    private final ProductRepository productRepository;
    private final ProductEventProducer eventProducer;
    private final ProductEmbeddingRefreshService productEmbeddingRefreshService;

    // event 추가
    @Override
    public Product create(ProductCreateRequest request, Long memberId){
        Product product = Product.create(memberId, request.name(), request.description(),
                request.price(),request.stock(),request.status(),request.category(),
                request.saleType(),request.brand(),memberId,
                request.startPrice(), request.minIncrement(),
                request.startsAt(), request.endsAt());

        Product savedProduct = productRepository.save(product);
        productEmbeddingRefreshService.refreshAfterCommit(savedProduct);
        //경매 상품이면 Auction 도메인에 발행
        if (savedProduct.getSaleType() == SaleType.AUCTION){
            eventProducer.sendAuctionRegistered(
                    new ProductRegisteredForAuctionEvent(
                            savedProduct.getId(),
                            savedProduct.getSellerId(),
                            request.startPrice(),
                            request.minIncrement(),
                            request.startsAt(),
                            request.endsAt()
                    )
            );
        }
        return savedProduct;

    }

    @Override
    public Product update(Long id, ProductUpdateRequest request, Long memberId){
        Product product = productRepository.findById(id)
                .orElseThrow( () -> new ResponseStatusException(HttpStatus.NOT_FOUND,"상품을 찾을 수 없습니다."));

        if (!product.getSellerId().equals(memberId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "본인의 상품만 수정할 수 있습니다.");
        }

        product.update(request.name(),request.description(),request.price(),request.stock(),
                request.status(),request.category(),request.brand(),memberId);
        Product savedProduct = productRepository.save(product);
        productEmbeddingRefreshService.refreshAfterCommit(savedProduct);
        return savedProduct;
    }

    @Override
    public void delete(Long id, Long memberId){
        Product product = productRepository.findById(id)
                .orElseThrow( () -> new ResponseStatusException(HttpStatus.NOT_FOUND,"상품을 찾을 수 없습니다."));

        if (!product.getSellerId().equals(memberId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "본인의 상품만 삭제할 수 있습니다.");
        }

        productRepository.delete(product);
    }
}
