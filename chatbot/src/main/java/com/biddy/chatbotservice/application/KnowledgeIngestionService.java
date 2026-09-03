package com.biddy.chatbotservice.application;

import com.biddy.chatbotservice.config.RagProperties;
import com.biddy.chatbotservice.domain.model.DocumentChunk;
import com.biddy.chatbotservice.infra.gemini.GeminiEmbeddingClient;
import com.biddy.chatbotservice.infra.persistence.DocumentChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * knowledge/*.md 문서를 청크 단위로 쪼개고, 각 청크를 임베딩해서 pgvector 테이블에 적재한다.
 * 청크는 "## " 로 시작하는 소제목(Q&A 하나) 단위로 나눈다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeIngestionService {

    private static final Pattern SECTION_SPLIT = Pattern.compile("\\n(?=## )");

    private final RagProperties ragProperties;
    private final GeminiEmbeddingClient embeddingClient;
    private final DocumentChunkRepository documentChunkRepository;

    /**
     * 전체 재적재. 기존 청크를 모두 지우고 knowledge 문서를 다시 읽어 임베딩한다.
     *
     * @return 새로 적재된 청크 수
     */
    public int reingestAll() {
        documentChunkRepository.deleteAll();
        List<DocumentChunk> chunks = loadChunks();
        for (DocumentChunk chunk : chunks) {
            float[] embedding = embeddingClient.embed(chunk.content(), GeminiEmbeddingClient.TASK_TYPE_DOCUMENT);
            documentChunkRepository.save(chunk.source(), chunk.chunkIndex(), chunk.content(), embedding);
        }
        log.info("Knowledge ingestion 완료: {}개 청크 적재", chunks.size());
        return chunks.size();
    }

    /**
     * 앱 시작 시 사용: 이미 적재된 청크가 있으면 건너뛴다.
     */
    public void ingestIfEmpty() {
        if (documentChunkRepository.count() > 0) {
            log.info("document_chunk 테이블에 이미 데이터가 있어 초기 적재를 건너뜁니다.");
            return;
        }
        reingestAll();
    }

    List<DocumentChunk> loadChunks() {
        List<DocumentChunk> result = new ArrayList<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources(ragProperties.chunkSourcePath());

            for (Resource resource : resources) {
                String filename = resource.getFilename() == null ? "unknown.md" : resource.getFilename();
                String text = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                result.addAll(splitIntoChunks(filename, text));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("knowledge 문서를 읽는 중 오류가 발생했습니다.", e);
        }
        return result;
    }

    private List<DocumentChunk> splitIntoChunks(String source, String text) {
        List<DocumentChunk> chunks = new ArrayList<>();
        String[] parts = SECTION_SPLIT.split(text.trim());

        // parts[0]은 문서 제목(# ...)이고, 그 뒤로 "## Q: ..." 섹션들이 이어진다.
        String title = parts[0].trim();

        int index = 0;
        for (int i = 1; i < parts.length; i++) {
            String section = parts[i].trim();
            if (section.isEmpty()) {
                continue;
            }
            String content = title + "\n\n" + section;
            chunks.add(new DocumentChunk(source, index++, content));
        }
        return chunks;
    }
}
