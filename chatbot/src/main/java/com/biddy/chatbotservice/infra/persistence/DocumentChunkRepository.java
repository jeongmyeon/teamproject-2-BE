package com.biddy.chatbotservice.infra.persistence;

import com.biddy.chatbotservice.domain.model.RetrievedChunk;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * pgvector 확장을 사용하는 document_chunk 테이블에 대한 저장/유사도 검색을 담당한다.
 * Hibernate/JPA 없이 JdbcTemplate으로 직접 SQL을 다루는 이유는, pgvector 타입을
 * 벡터 리터럴 문자열("[0.1,0.2,...]"::vector)로 캐스팅해서 다루는 편이
 * 별도 라이브러리 없이 가장 투명하고 단순하기 때문이다.
 */
@Repository
@RequiredArgsConstructor
public class DocumentChunkRepository {

    private final JdbcTemplate jdbcTemplate;

    public int count() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM document_chunk", Integer.class);
        return count == null ? 0 : count;
    }

    public void deleteAll() {
        jdbcTemplate.update("DELETE FROM document_chunk");
    }

    public void save(String source, int chunkIndex, String content, float[] embedding) {
        jdbcTemplate.update(
                """
                INSERT INTO document_chunk (source, chunk_index, content, embedding)
                VALUES (?, ?, ?, ?::vector)
                ON CONFLICT (source, chunk_index) DO UPDATE
                    SET content = EXCLUDED.content, embedding = EXCLUDED.embedding
                """,
                source, chunkIndex, content, toVectorLiteral(embedding)
        );
    }

    public List<RetrievedChunk> findSimilar(float[] queryEmbedding, int topK) {
        String vectorLiteral = toVectorLiteral(queryEmbedding);
        return jdbcTemplate.query(
                """
                SELECT source, content, 1 - (embedding <=> ?::vector) AS similarity
                FROM document_chunk
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """,
                (rs, rowNum) -> new RetrievedChunk(
                        rs.getString("source"),
                        rs.getString("content"),
                        rs.getDouble("similarity")
                ),
                vectorLiteral, vectorLiteral, topK
        );
    }

    private String toVectorLiteral(float[] values) {
        return IntStream.range(0, values.length)
                .mapToObj(i -> Float.toString(values[i]))
                .collect(Collectors.joining(",", "[", "]"));
    }
}
