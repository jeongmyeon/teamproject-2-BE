package com.biddy.productservice.domain.model;


import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@Table(name = "\"product\"", schema = "public")
@Schema(description = "상품 정보")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Schema(description = "상품 ID", example = "1", accessMode = Schema.AccessMode.READ_ONLY)
    private Long id;

    @Version
    @Column(name = "version", columnDefinition = "BIGINT DEFAULT 0")
    private Long version = 0L;

    @Column(name = "\"name\"",nullable = false,length = 255)
    @Schema(description = "상품명", example = "홍길동 판매소")
    private String name;

    @Column(name = "description", nullable = false, columnDefinition = "text")
    @Schema(description = "상품설명",example = "2026 한정판 굿즈")
    private String description;

    @Column(name = "price",nullable = false)
    @Schema(description = "상품가격",example = "50000")
    private BigDecimal price;

    @Column(name = "stock",nullable = false)
    @Schema(description = "상품재고",example = "10")
    private int stock;

    @Column(nullable = false,length = 20)
    @Schema(description = "상품상태",example = "ACTIVE")
    private String status;

    @Column(name = "category", nullable = false,length = 50)
    @Schema(description = "상품 카테고리",example = "한정판 굿즈")
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(name = "sale_type",nullable = false,length = 20)
    @Schema(description = "판매 유형",example = "NORMAL")
    private SaleType saleType;

    @Column(name = "brand",length = 100)
    @Schema(description = "브랜드",example = "나이키")
    private String brand;

    // 경매 전용 필드
    @Column(name = "start_price")
    @Schema(description = "경매 시작가", example = "5000")
    private java.math.BigDecimal startPrice;

    @Column(name = "min_increment")
    @Schema(description = "최소 입찰 단위", example = "500")
    private Integer minIncrement;

    @Column(name = "starts_at")
    @Schema(description = "경매 시작일시")
    private java.time.LocalDateTime startsAt;

    @Column(name = "ends_at")
    @Schema(description = "경매 종료일시")
    private java.time.LocalDateTime endsAt;

    @ElementCollection
    @CollectionTable(name = "product_image_urls", joinColumns = @JoinColumn(name = "product_id"))
    @Column(name = "image_url")
    @Schema(description = "상품 이미지 URL 목록")
    private List<String> imageUrls = new ArrayList<>();

    @Column(name = "reg_dt",nullable = false)
    @Schema(description = "등록일시",example = "2026-06-15T18:10:00",accessMode = Schema.AccessMode.READ_ONLY)
    private LocalDateTime regDt;

    @Column(name = "modify_id", nullable = false)
    @Schema(description = "수정자 ID", example = "1")
    private Long modifyId;

    @Column(name = "modify_dt",nullable = false)
    @Schema(description = "수정일시",example = "2026-06-16T18:30:00",accessMode = Schema.AccessMode.READ_ONLY)
    private LocalDateTime modifyDt;

    @Column(name = "seller_id",nullable = false)
    @Schema(description = "판매자 ID", example = "1")
    private Long sellerId;

    @Column(name = "reg_id",nullable = false)
    @Schema(description = "등록자 ID", example = "1")
    private Long regId;

    protected Product(){}

    private Product(Long sellerId, String name, String description, BigDecimal price, int stock, String status, String category,
                    SaleType saleType, String brand) {
        this.sellerId = sellerId;
        this.name = name;
        this.description = description;
        this.price = price;
        this.stock = stock;
        this.status = status;
        this.category = category;
        this.saleType = saleType;
        this.brand = brand;
    }

    public static Product create(Long sellerId, String name, String description, BigDecimal price,
                                 int stock, String status, String category,
                                 SaleType saleType, String brand, Long creatorId)
    {
        Product product = new Product(sellerId, name, description, price,
                stock, status, category, saleType, brand);
        product.regId = creatorId;
        product.modifyId = creatorId;
        return product;
    }

    public static Product create(Long sellerId, String name, String description, BigDecimal price,
                                 int stock, String status, String category,
                                 SaleType saleType, String brand, Long creatorId,
                                 java.math.BigDecimal startPrice, Integer minIncrement,
                                 java.time.LocalDateTime startsAt, java.time.LocalDateTime endsAt)
    {
        Product product = new Product(sellerId, name, description, price,
                stock, status, category, saleType, brand);
        product.regId = creatorId;
        product.modifyId = creatorId;
        product.startPrice = startPrice;
        product.minIncrement = minIncrement;
        product.startsAt = startsAt;
        product.endsAt = endsAt;
        return product;
    }

    public void update(String name, String description, BigDecimal price, int stock, String status,
                       String category, String brand, Long modifyId){
        this.name = name;
        this.description = description;
        this.price = price;
        this.stock = stock;
        this.status = status;
        this.category = category;
        this.brand = brand;
        this.modifyId = modifyId;
    }
    public void deductStock(int quantity) {
        if (this.stock < quantity) {
            throw new IllegalStateException("재고 부족");
        }
        this.stock -= quantity;
    }

    public void restoreStock(int quantity) {
        this.stock += quantity;
    }

    public void changeStatus(String status) {
        this.status = status;
    }

    public void addImageUrls(List<String> urls) {
        if (this.imageUrls.size() + urls.size() > 5) {
            throw new IllegalStateException("이미지는 최대 5장까지 등록 가능합니다.");
        }
        this.imageUrls.addAll(urls);
    }

    public void removeImageUrl(String url) {
        if (!this.imageUrls.remove(url)) {
            throw new IllegalArgumentException("해당 이미지가 상품에 등록되어 있지 않습니다.");
        }
    }
    @PrePersist
    public void onCreate(){
        if (regDt == null) { regDt = LocalDateTime.now();}
        if (modifyDt == null) { modifyDt = regDt;}
        if (status == null) { status = "ACTIVE";}
    }

    @PreUpdate
    public void onUpdate() {
        modifyDt = LocalDateTime.now();
    }
}








