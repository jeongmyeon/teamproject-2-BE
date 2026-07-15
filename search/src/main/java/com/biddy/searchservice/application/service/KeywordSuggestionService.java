package com.biddy.searchservice.application.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.SortOrder;
import com.biddy.searchservice.infrastructure.elasticsearch.SearchKeywordDocument;
import com.biddy.searchservice.presentation.dto.KeywordSuggestionResponse;
import com.biddy.searchservice.presentation.dto.PopularKeywordResponse;
import com.biddy.searchservice.presentation.dto.PopularKeywordResult;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class KeywordSuggestionService {

    private final ElasticsearchClient elasticsearchClient;

    @Value("${biddy.search.keyword-index:search-keywords}")
    private String keywordIndex;

    public KeywordSuggestionResponse suggest(String keyword, int size) {
        String normalizedKeyword = normalize(keyword);
        if (normalizedKeyword.isBlank()) {
            return new KeywordSuggestionResponse(keyword, List.of());
        }

        try {
            List<String> suggestions = elasticsearchClient.search(search -> search
                                    .index(keywordIndex)
                                    .size(safeSize(size))
                                    .query(query -> query
                                            .matchPhrasePrefix(match -> match
                                                    .field("keyword")
                                                    .query(normalizedKeyword)
                                            )
                                    )
                                    .sort(sort -> sort.field(field -> field
                                            .field("count")
                                            .order(SortOrder.Desc)
                                    ))
                                    .sort(sort -> sort.field(field -> field
                                            .field("updatedAt")
                                            .order(SortOrder.Desc)
                                    )),
                            SearchKeywordDocument.class)
                    .hits()
                    .hits()
                    .stream()
                    .map(hit -> hit.source() == null ? null : hit.source().keyword())
                    .filter(value -> value != null && !value.isBlank())
                    .distinct()
                    .toList();

            return new KeywordSuggestionResponse(keyword, suggestions);
        } catch (ElasticsearchException | IOException e) {
            return new KeywordSuggestionResponse(keyword, List.of());
        }
    }

    public PopularKeywordResponse popular(int size) {
        try {
            List<PopularKeywordResult> keywords = elasticsearchClient.search(search -> search
                                    .index(keywordIndex)
                                    .size(safeSize(size))
                                    .query(query -> query.matchAll(matchAll -> matchAll))
                                    .sort(sort -> sort.field(field -> field
                                            .field("count")
                                            .order(SortOrder.Desc)
                                    ))
                                    .sort(sort -> sort.field(field -> field
                                            .field("updatedAt")
                                            .order(SortOrder.Desc)
                                    )),
                            SearchKeywordDocument.class)
                    .hits()
                    .hits()
                    .stream()
                    .map(hit -> hit.source() == null
                            ? null
                            : new PopularKeywordResult(hit.source().keyword(), safeCount(hit.source())))
                    .filter(value -> value != null && value.keyword() != null && !value.keyword().isBlank())
                    .toList();

            return new PopularKeywordResponse(keywords);
        } catch (ElasticsearchException | IOException e) {
            return new PopularKeywordResponse(List.of());
        }
    }

    public void save(String keyword) {
        String normalizedKeyword = normalize(keyword);
        if (normalizedKeyword.isBlank()) {
            return;
        }

        long nextCount = findKeyword(normalizedKeyword)
                .map(document -> safeCount(document) + 1)
                .orElse(1L);

        SearchKeywordDocument document = new SearchKeywordDocument(
                normalizedKeyword,
                nextCount,
                Instant.now().toString()
        );

        try {
            elasticsearchClient.index(index -> index
                    .index(keywordIndex)
                    .id(normalizedKeyword)
                    .document(document));
        } catch (ElasticsearchException | IOException ignored) {
            // Elasticsearch 자동완성 저장 실패가 상품 검색 자체를 막지는 않게 둔다.
        }
    }

    private java.util.Optional<SearchKeywordDocument> findKeyword(String keyword) {
        try {
            var response = elasticsearchClient.get(get -> get
                            .index(keywordIndex)
                            .id(keyword),
                    SearchKeywordDocument.class);
            return response.found() ? java.util.Optional.ofNullable(response.source()) : java.util.Optional.empty();
        } catch (ElasticsearchException | IOException e) {
            return java.util.Optional.empty();
        }
    }

    private long safeCount(SearchKeywordDocument document) {
        return document.count() == null ? 0L : document.count();
    }

    private int safeSize(int size) {
        return Math.min(Math.max(size, 1), 30);
    }

    private String normalize(String keyword) {
        return keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
    }
}
