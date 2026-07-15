package com.biddy.productservice.infra.embedding;

import java.util.List;

public interface TextEmbeddingClient {

    List<Double> embed(String text);

    String model();

    int dimensions();
}
