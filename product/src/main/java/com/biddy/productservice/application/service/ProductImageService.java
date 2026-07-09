package com.biddy.productservice.application.service;

import com.biddy.productservice.domain.model.Product;
import com.biddy.productservice.domain.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProductImageService {

    private final ProductRepository productRepository;
    private final S3Client s3Client;

    @Value("${aws.s3.bucket:team03-biddy-storage}")
    private String bucket;

    @Value("${image.base.url}")
    private String baseUrl;

    @Transactional
    public List<String> uploadImages(Long productId, List<MultipartFile> files) throws IOException {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("상품을 찾을 수 없습니다."));

        List<String> savedUrls = files.stream().map(file -> {
            String fileName = "product-images/" + UUID.randomUUID() + extension(file.getOriginalFilename());
            try {
                s3Client.putObject(
                        PutObjectRequest.builder()
                                .bucket(bucket)
                                .key(fileName)
                                .contentType(file.getContentType())
                                .build(),
                        RequestBody.fromInputStream(file.getInputStream(), file.getSize())
                );
            } catch (IOException e) {
                throw new RuntimeException("이미지 저장 실패: " + file.getOriginalFilename(), e);
            }
            return baseUrl + "/" + fileName;
        }).toList();

        product.addImageUrls(savedUrls);
        productRepository.save(product);

        return savedUrls;
    }

    private String extension(String originalFilename) {
        if (originalFilename == null || !originalFilename.contains(".")) {
            return "";
        }
        return originalFilename.substring(originalFilename.lastIndexOf("."));
    }
}
