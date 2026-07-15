package com.biddy.productservice.presentation.controller;


import com.biddy.productservice.application.service.ProductImageService;
import com.biddy.productservice.application.service.ProductLikeService;
import com.biddy.productservice.application.service.ProductVectorSearchService;
import com.biddy.productservice.application.usecase.ProductCommandUseCase;
import com.biddy.productservice.application.usecase.ProductQueryUseCase;
import com.biddy.productservice.domain.model.Product;
import com.biddy.productservice.domain.model.SaleType;
import com.biddy.productservice.presentation.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("${api.init}/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductCommandUseCase productCommandUseCase;
    private final ProductQueryUseCase productQueryUseCase;
    private final ProductImageService productImageService;
    private final ProductLikeService productLikeService;
    private final ProductVectorSearchService productVectorSearchService;

    @PostMapping
    @Operation(summary = "상품 등록",description = "상품을 새로 등록합니다. 로그인 필요.")
    @ApiResponses({
            @ApiResponse(responseCode="201",description="생성 성공",
            content = @Content(schema = @Schema(implementation = Product.class))),
            @ApiResponse(responseCode="400",description="요청 오류"),
            @ApiResponse(responseCode="401",description="인증 필요")
    })
    public ResponseEntity<Product> create(
            @Valid @RequestBody ProductCreateRequest request){
        Long memberId = Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());
        Product response = productCommandUseCase.create(request, memberId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    @Operation(summary = "상품 수정",description = "상품 정보를 수정합니다. 로그인 필요.")
    @ApiResponses({
            @ApiResponse(responseCode="200",description="수정 성공",
                    content = @Content(schema = @Schema(implementation = Product.class))),
            @ApiResponse(responseCode="404",description="상품 없음"),
            @ApiResponse(responseCode="401",description="인증 필요")
    })
    public ResponseEntity<Product> update(
            @PathVariable Long id,
            @Valid @RequestBody ProductUpdateRequest request){
        Long memberId = Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());
        return ResponseEntity.ok(productCommandUseCase.update(id, request, memberId));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "상품 삭제", description = "상품을 삭제합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "삭제 성공"),
            @ApiResponse(responseCode = "404", description = "상품 없음")
    })
    public ResponseEntity<Void> delete(@PathVariable Long id){
        Long memberId = Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());
        productCommandUseCase.delete(id, memberId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    @Operation(summary = "상품 목록 조회",description = "전체 또는 판매유형별 상품 목록을 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200",description = "조회 성공")
    })
    public List<Product> getProducts(
            @RequestParam(required = false) SaleType saleType){
        if (saleType != null) {
            return productQueryUseCase.getBySaleType(saleType);
        }
        return productQueryUseCase.getAll();
    }

    @PostMapping("/search/vector")
    @Operation(summary = "상품 벡터 후보 조회", description = "Search Service가 전달한 검색어 임베딩으로 유사 상품 후보를 조회합니다.")
    public List<ProductVectorSearchResponse> vectorSearch(
            @RequestBody ProductVectorSearchRequest request
    ) {
        return productVectorSearchService.search(request.queryEmbedding(), request.size());
    }

    @GetMapping("/{id}")
    @Operation(summary = "상품 단일 조회",description = "Id로 상품 정보를 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(schema = @Schema(implementation = Product.class))),
            @ApiResponse(responseCode = "404", description = "상품 없음")
    })
    public Product getById(@PathVariable Long id){
        return productQueryUseCase.getById(id);
    }


    @PostMapping("/{id}/images")
    @Operation(summary = "상품 이미지 업로드", description = "상품에 이미지를 등록합니다. 최대 5장")
    public ResponseEntity<List<String>> uploadImages(
            @PathVariable Long id,
            @RequestParam("images") List<MultipartFile> images) throws IOException {
        List<String> urls = productImageService.uploadImages(id, images);
        return ResponseEntity.status(HttpStatus.CREATED).body(urls);
    }

    @DeleteMapping("/{id}/images")
    @Operation(summary = "상품 이미지 삭제", description = "상품 이미지를 하나 삭제합니다.")
    public ResponseEntity<Void> deleteImage(
            @PathVariable Long id,
            @RequestParam("url") String url) {
        productImageService.deleteImage(id, url);
        return ResponseEntity.noContent().build();
    }

    // ── 찜하기 ──────────────────────────────────────────────

    @PostMapping("/{id}/like")
    @Operation(summary = "찜하기", description = "상품을 찜합니다. 로그인 필요.")
    public ResponseEntity<Void> like(@PathVariable Long id) {
        Long memberId = Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());
        productLikeService.like(id, memberId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}/like")
    @Operation(summary = "찜 취소", description = "찜을 취소합니다. 로그인 필요.")
    public ResponseEntity<Void> unlike(@PathVariable Long id) {
        Long memberId = Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());
        productLikeService.unlike(id, memberId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/like-count")
    @Operation(summary = "찜 개수 조회")
    public ResponseEntity<Map<String, Integer>> getLikeCount(@PathVariable Long id) {
        return ResponseEntity.ok(Map.of("likeCount", productLikeService.getLikeCount(id)));
    }

    @GetMapping("/{id}/is-liked")
    @Operation(summary = "찜 여부 조회", description = "비로그인이면 false 반환")
    public ResponseEntity<Map<String, Boolean>> isLiked(@PathVariable Long id) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            return ResponseEntity.ok(Map.of("liked", false));
        }
        Long memberId = Long.parseLong(auth.getName());
        return ResponseEntity.ok(Map.of("liked", productLikeService.isLiked(id, memberId)));
    }

    @GetMapping("/liked")
    @Operation(summary = "내 찜 목록 조회", description = "로그인한 회원의 찜 목록을 반환합니다.")
    public List<Product> getLikedProducts() {
        Long memberId = Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());
        return productLikeService.getLikedProducts(memberId);
    }

    // ────────────────────────────────────────────────────────

    @PostMapping("/details")
    public List<ProductInfoResponse> getProductsInfo(
            @RequestBody ProductIdsRequest request
    ) {
        return productQueryUseCase.getProductsByIds(request.productIds())
                .stream()
                .map(product -> new ProductInfoResponse(
                        product.getId(),
                        product.getName(),
                        product.getPrice(),
                        product.getStatus(),
                        product.getSellerId(),
                        product.getStock()
                ))
                .toList();
    }

}
