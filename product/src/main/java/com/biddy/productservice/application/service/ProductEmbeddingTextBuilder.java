package com.biddy.productservice.application.service;

import com.biddy.productservice.domain.model.Product;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Component
public class ProductEmbeddingTextBuilder {

    public String build(Product product) {
        List<String> lines = new ArrayList<>();
        addLine(lines, "상품명", product.getName());
        addLine(lines, "브랜드", product.getBrand());
        addLine(lines, "카테고리", product.getCategory());
        addLine(lines, "상품설명", product.getDescription());
        return String.join("\n", lines);
    }

    private void addLine(List<String> lines, String label, String value) {
        if (StringUtils.hasText(value)) {
            lines.add(label + ": " + value.trim());
        }
    }
}
