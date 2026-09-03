# RAG 검색 정확도(Top-K) 평가 도구 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `chatbot` 서비스에 기존 29개 FAQ 청크를 정답지 삼아 Top-K별 검색 정확도(Accuracy@K)를 계산하는 평가 도구를 추가하고, `/api/chatbot/admin/eval/retrieval-accuracy` REST 엔드포인트로 노출한다.

**Architecture:** 청크 → 질문 추출(`EvalQuestionExtractor`) → 질문별 정답 순위 조회(`RetrievalRankFinder`, Gemini 임베딩 + pgvector 검색) → 순위 목록으로 K별 정확도 계산(`RetrievalAccuracyCalculator`, 순수 함수) 순으로 파이프라인을 구성한다. 오케스트레이션은 `RetrievalAccuracyEvalService`가 맡고, `ChatbotController`가 REST로 노출한다.

**Tech Stack:** Spring Boot, JUnit 5 + Mockito + AssertJ (spring-boot-starter-test), 참조 스펙: [`docs/superpowers/specs/2026-09-04-rag-retrieval-accuracy-eval-design.md`](../specs/2026-09-04-rag-retrieval-accuracy-eval-design.md)

## Global Constraints

- 별도 질의셋 파일을 새로 만들지 않는다 — `KnowledgeIngestionService`가 이미 만드는 29개 청크를 재사용한다.
- 정답 판정은 검색된 청크의 `content()`가 평가 질문의 `expectedContent`와 **완전히 일치**하는지로 판단한다 (source만으로 비교하지 않는다 — 한 파일에 여러 Q&A가 있어 오탐이 생기기 때문).
- 순위 계산(`RetrievalAccuracyCalculator`)은 DB·외부 API에 의존하지 않는 순수 함수로 만든다 — 이 세션(DB·Gemini 키 없음)에서도 단위 테스트로 검증하기 위함.
- `chatbot` 모듈에는 아직 `src/test` 디렉터리가 없다 — Task 1에서 새로 만든다.
- 이번 범위는 검색 정확도(Recall@K)까지다. 생성 답변의 근거성 자동 판정(LLM-as-judge)은 범위 밖.

---

### Task 1: EvalQuestionExtractor — 청크에서 평가 질문 추출

**Files:**
- Create: `chatbot/src/main/java/com/biddy/chatbotservice/domain/model/EvalQuestion.java`
- Create: `chatbot/src/main/java/com/biddy/chatbotservice/application/EvalQuestionExtractor.java`
- Test: `chatbot/src/test/java/com/biddy/chatbotservice/application/EvalQuestionExtractorTest.java`

**Interfaces:**
- Consumes: `DocumentChunk(String source, int chunkIndex, String content)` (기존 record, 변경 없음)
- Produces: `EvalQuestion(String question, String expectedSource, String expectedContent)`, `EvalQuestionExtractor.extract(List<DocumentChunk>) -> List<EvalQuestion>`. Task 4에서 이 시그니처를 그대로 사용한다.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.biddy.chatbotservice.application;

import com.biddy.chatbotservice.domain.model.DocumentChunk;
import com.biddy.chatbotservice.domain.model.EvalQuestion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvalQuestionExtractorTest {

    private final EvalQuestionExtractor extractor = new EvalQuestionExtractor();

    @Test
    void Q_패턴이_있는_청크에서_질문과_출처_정답청크를_추출한다() {
        DocumentChunk chunk = new DocumentChunk(
                "01_회원.md",
                0,
                "# Biddy 회원 관련 안내\n\n## Q: 회원가입은 어떻게 하나요?\nA: 이메일로 인증코드를 받아야 합니다."
        );

        List<EvalQuestion> result = extractor.extract(List.of(chunk));

        assertThat(result).hasSize(1);
        EvalQuestion evalQuestion = result.get(0);
        assertThat(evalQuestion.question()).isEqualTo("회원가입은 어떻게 하나요?");
        assertThat(evalQuestion.expectedSource()).isEqualTo("01_회원.md");
        assertThat(evalQuestion.expectedContent()).isEqualTo(chunk.content());
    }

    @Test
    void Q_패턴이_없는_청크는_건너뛴다() {
        DocumentChunk chunk = new DocumentChunk("02_상품.md", 0, "# 제목만 있고 질문 패턴은 없음");

        List<EvalQuestion> result = extractor.extract(List.of(chunk));

        assertThat(result).isEmpty();
    }

    @Test
    void 여러_청크_중_일부만_패턴이_있으면_있는_것만_추출한다() {
        DocumentChunk withQuestion = new DocumentChunk("a.md", 0, "# 제목\n\n## Q: 질문입니다\nA: 답입니다");
        DocumentChunk withoutQuestion = new DocumentChunk("b.md", 0, "# 제목만");

        List<EvalQuestion> result = extractor.extract(List.of(withQuestion, withoutQuestion));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).question()).isEqualTo("질문입니다");
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패(컴파일 에러) 확인**

Run: `./gradlew :chatbot:test --tests "com.biddy.chatbotservice.application.EvalQuestionExtractorTest"`
Expected: FAIL — `EvalQuestion`, `EvalQuestionExtractor` 클래스가 없어 컴파일 실패

- [ ] **Step 3: `EvalQuestion` 레코드 작성**

```java
package com.biddy.chatbotservice.domain.model;

/**
 * 검색 정확도 평가용 질문 1건. knowledge 청크에서 추출한 질문과,
 * 그 질문의 정답이 되어야 할 청크 정보(출처, 전체 내용).
 */
public record EvalQuestion(
        String question,
        String expectedSource,
        String expectedContent
) {
}
```

- [ ] **Step 4: `EvalQuestionExtractor` 작성**

```java
package com.biddy.chatbotservice.application;

import com.biddy.chatbotservice.domain.model.DocumentChunk;
import com.biddy.chatbotservice.domain.model.EvalQuestion;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * knowledge 청크 목록에서 "## Q: ..." 질문 텍스트를 추출해 평가용 질의셋으로 변환한다.
 */
@Slf4j
@Component
public class EvalQuestionExtractor {

    private static final Pattern QUESTION_LINE = Pattern.compile("^## Q: (.+)$", Pattern.MULTILINE);

    public List<EvalQuestion> extract(List<DocumentChunk> chunks) {
        List<EvalQuestion> result = new ArrayList<>();
        for (DocumentChunk chunk : chunks) {
            Matcher matcher = QUESTION_LINE.matcher(chunk.content());
            if (!matcher.find()) {
                log.warn("질문 패턴을 찾을 수 없어 평가 대상에서 제외: source={}, chunkIndex={}",
                        chunk.source(), chunk.chunkIndex());
                continue;
            }
            result.add(new EvalQuestion(matcher.group(1).trim(), chunk.source(), chunk.content()));
        }
        return result;
    }
}
```

- [ ] **Step 5: 테스트 실행해서 통과 확인**

Run: `./gradlew :chatbot:test --tests "com.biddy.chatbotservice.application.EvalQuestionExtractorTest"`
Expected: PASS (3 tests)

- [ ] **Step 6: 커밋**

```bash
git add chatbot/src/main/java/com/biddy/chatbotservice/domain/model/EvalQuestion.java chatbot/src/main/java/com/biddy/chatbotservice/application/EvalQuestionExtractor.java chatbot/src/test/java/com/biddy/chatbotservice/application/EvalQuestionExtractorTest.java
git commit -m "feat(chatbot): knowledge 청크에서 평가 질문 추출하는 EvalQuestionExtractor 추가"
```

---

### Task 2: RetrievalAccuracyCalculator — Top-K별 정확도 계산 (순수 함수)

**Files:**
- Create: `chatbot/src/main/java/com/biddy/chatbotservice/domain/model/QuestionRankResult.java`
- Create: `chatbot/src/main/java/com/biddy/chatbotservice/presentation/dto/RetrievalAccuracyReport.java`
- Create: `chatbot/src/main/java/com/biddy/chatbotservice/application/RetrievalAccuracyCalculator.java`
- Test: `chatbot/src/test/java/com/biddy/chatbotservice/application/RetrievalAccuracyCalculatorTest.java`

**Interfaces:**
- Consumes: 없음 (순수 함수, 외부 의존성 없음)
- Produces: `QuestionRankResult(String question, Optional<Integer> rank)`, `RetrievalAccuracyReport(int totalQuestions, List<TopKAccuracy> results, List<String> missedQuestions)` (`TopKAccuracy(int k, int hitCount, double accuracy)`), `RetrievalAccuracyCalculator.calculate(List<QuestionRankResult>, List<Integer>) -> RetrievalAccuracyReport`. Task 3~4에서 이 시그니처를 그대로 사용한다.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.biddy.chatbotservice.application;

import com.biddy.chatbotservice.domain.model.QuestionRankResult;
import com.biddy.chatbotservice.presentation.dto.RetrievalAccuracyReport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetrievalAccuracyCalculatorTest {

    @Test
    void 모든_질문이_1위로_정답을_찾으면_K1에서_정확도가_1_0이다() {
        List<QuestionRankResult> results = List.of(
                new QuestionRankResult("q1", Optional.of(1)),
                new QuestionRankResult("q2", Optional.of(1))
        );

        RetrievalAccuracyReport report = RetrievalAccuracyCalculator.calculate(results, List.of(1));

        assertThat(report.totalQuestions()).isEqualTo(2);
        assertThat(report.results().get(0).k()).isEqualTo(1);
        assertThat(report.results().get(0).hitCount()).isEqualTo(2);
        assertThat(report.results().get(0).accuracy()).isEqualTo(1.0);
    }

    @Test
    void K값별로_다른_정확도를_계산한다() {
        List<QuestionRankResult> results = List.of(
                new QuestionRankResult("q1", Optional.of(1)),
                new QuestionRankResult("q2", Optional.of(3)),
                new QuestionRankResult("q3", Optional.of(5)),
                new QuestionRankResult("q4", Optional.empty())
        );

        RetrievalAccuracyReport report = RetrievalAccuracyCalculator.calculate(results, List.of(1, 4, 8));

        assertThat(report.results().get(0).k()).isEqualTo(1);
        assertThat(report.results().get(0).hitCount()).isEqualTo(1);
        assertThat(report.results().get(1).k()).isEqualTo(4);
        assertThat(report.results().get(1).hitCount()).isEqualTo(2);
        assertThat(report.results().get(2).k()).isEqualTo(8);
        assertThat(report.results().get(2).hitCount()).isEqualTo(3);
    }

    @Test
    void 가장_큰_K로도_못_찾은_질문은_missedQuestions에_포함된다() {
        List<QuestionRankResult> results = List.of(
                new QuestionRankResult("q1", Optional.of(1)),
                new QuestionRankResult("q2", Optional.of(10)),
                new QuestionRankResult("q3", Optional.empty())
        );

        RetrievalAccuracyReport report = RetrievalAccuracyCalculator.calculate(results, List.of(1, 4));

        assertThat(report.missedQuestions()).containsExactlyInAnyOrder("q2", "q3");
    }

    @Test
    void kValues가_비어있으면_예외를_던진다() {
        assertThatThrownBy(() -> RetrievalAccuracyCalculator.calculate(List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패(컴파일 에러) 확인**

Run: `./gradlew :chatbot:test --tests "com.biddy.chatbotservice.application.RetrievalAccuracyCalculatorTest"`
Expected: FAIL — `QuestionRankResult`, `RetrievalAccuracyReport`, `RetrievalAccuracyCalculator`가 없어 컴파일 실패

- [ ] **Step 3: `QuestionRankResult` 레코드 작성**

```java
package com.biddy.chatbotservice.domain.model;

import java.util.Optional;

/**
 * 평가 질문 1건에 대해, 정답 청크가 검색 결과에서 몇 번째 순위(1부터)로 나왔는지.
 * maxK 안에서 못 찾았으면 empty.
 */
public record QuestionRankResult(
        String question,
        Optional<Integer> rank
) {
}
```

- [ ] **Step 4: `RetrievalAccuracyReport` DTO 작성**

```java
package com.biddy.chatbotservice.presentation.dto;

import java.util.List;

public record RetrievalAccuracyReport(
        int totalQuestions,
        List<TopKAccuracy> results,
        List<String> missedQuestions
) {
    public record TopKAccuracy(int k, int hitCount, double accuracy) {
    }
}
```

- [ ] **Step 5: `RetrievalAccuracyCalculator` 작성**

```java
package com.biddy.chatbotservice.application;

import com.biddy.chatbotservice.domain.model.QuestionRankResult;
import com.biddy.chatbotservice.presentation.dto.RetrievalAccuracyReport;

import java.util.ArrayList;
import java.util.List;

/**
 * 질문별 정답 순위 목록과 K값 목록을 받아 K별 정확도(Accuracy@K)를 계산하는 순수 함수.
 * DB·외부 API에 의존하지 않는다.
 */
public class RetrievalAccuracyCalculator {

    public static RetrievalAccuracyReport calculate(List<QuestionRankResult> rankResults, List<Integer> kValues) {
        if (kValues.isEmpty()) {
            throw new IllegalArgumentException("topKs 파라미터가 비어 있습니다.");
        }

        int total = rankResults.size();
        List<RetrievalAccuracyReport.TopKAccuracy> topKAccuracies = new ArrayList<>();
        for (int k : kValues) {
            long hitCount = rankResults.stream()
                    .filter(r -> r.rank().isPresent() && r.rank().get() <= k)
                    .count();
            double accuracy = total == 0 ? 0.0 : (double) hitCount / total;
            topKAccuracies.add(new RetrievalAccuracyReport.TopKAccuracy(k, (int) hitCount, accuracy));
        }

        int maxK = kValues.stream().max(Integer::compareTo).orElseThrow();
        List<String> missedQuestions = rankResults.stream()
                .filter(r -> r.rank().isEmpty() || r.rank().get() > maxK)
                .map(QuestionRankResult::question)
                .toList();

        return new RetrievalAccuracyReport(total, topKAccuracies, missedQuestions);
    }
}
```

- [ ] **Step 6: 테스트 실행해서 통과 확인**

Run: `./gradlew :chatbot:test --tests "com.biddy.chatbotservice.application.RetrievalAccuracyCalculatorTest"`
Expected: PASS (4 tests)

- [ ] **Step 7: 커밋**

```bash
git add chatbot/src/main/java/com/biddy/chatbotservice/domain/model/QuestionRankResult.java chatbot/src/main/java/com/biddy/chatbotservice/presentation/dto/RetrievalAccuracyReport.java chatbot/src/main/java/com/biddy/chatbotservice/application/RetrievalAccuracyCalculator.java chatbot/src/test/java/com/biddy/chatbotservice/application/RetrievalAccuracyCalculatorTest.java
git commit -m "feat(chatbot): Top-K별 검색 정확도를 계산하는 순수 함수 RetrievalAccuracyCalculator 추가"
```

---

### Task 3: RetrievalRankFinder — 질문 1건의 정답 순위 조회

**Files:**
- Create: `chatbot/src/main/java/com/biddy/chatbotservice/application/RetrievalRankFinder.java`
- Test: `chatbot/src/test/java/com/biddy/chatbotservice/application/RetrievalRankFinderTest.java`

**Interfaces:**
- Consumes: `GeminiEmbeddingClient.embed(String, String) -> float[]` (기존), `DocumentChunkRepository.findSimilar(float[], int) -> List<RetrievedChunk>` (기존), `EvalQuestion` (Task 1)
- Produces: `RetrievalRankFinder.findRank(EvalQuestion, int maxK) -> Optional<Integer>`. Task 4에서 이 시그니처를 그대로 사용한다.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.biddy.chatbotservice.application;

import com.biddy.chatbotservice.domain.model.EvalQuestion;
import com.biddy.chatbotservice.domain.model.RetrievedChunk;
import com.biddy.chatbotservice.infra.gemini.GeminiEmbeddingClient;
import com.biddy.chatbotservice.infra.persistence.DocumentChunkRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class RetrievalRankFinderTest {

    @Mock private GeminiEmbeddingClient embeddingClient;
    @Mock private DocumentChunkRepository documentChunkRepository;

    @InjectMocks
    private RetrievalRankFinder retrievalRankFinder;

    @Test
    void 정답_청크가_검색결과_2번째에_있으면_순위_2를_반환한다() {
        EvalQuestion evalQuestion = new EvalQuestion("질문", "source.md", "정답 청크 내용");
        float[] embedding = {0.1f, 0.2f};
        given(embeddingClient.embed("질문", GeminiEmbeddingClient.TASK_TYPE_QUERY)).willReturn(embedding);
        given(documentChunkRepository.findSimilar(embedding, 4)).willReturn(List.of(
                new RetrievedChunk("other.md", "다른 청크 내용", 0.9),
                new RetrievedChunk("source.md", "정답 청크 내용", 0.85)
        ));

        Optional<Integer> rank = retrievalRankFinder.findRank(evalQuestion, 4);

        assertThat(rank).contains(2);
    }

    @Test
    void 정답_청크가_검색결과에_없으면_빈값을_반환한다() {
        EvalQuestion evalQuestion = new EvalQuestion("질문", "source.md", "정답 청크 내용");
        float[] embedding = {0.1f, 0.2f};
        given(embeddingClient.embed("질문", GeminiEmbeddingClient.TASK_TYPE_QUERY)).willReturn(embedding);
        given(documentChunkRepository.findSimilar(embedding, 4)).willReturn(List.of(
                new RetrievedChunk("other.md", "다른 청크 내용", 0.9)
        ));

        Optional<Integer> rank = retrievalRankFinder.findRank(evalQuestion, 4);

        assertThat(rank).isEmpty();
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패(컴파일 에러) 확인**

Run: `./gradlew :chatbot:test --tests "com.biddy.chatbotservice.application.RetrievalRankFinderTest"`
Expected: FAIL — `RetrievalRankFinder` 클래스가 없어 컴파일 실패

- [ ] **Step 3: `RetrievalRankFinder` 작성**

```java
package com.biddy.chatbotservice.application;

import com.biddy.chatbotservice.domain.model.EvalQuestion;
import com.biddy.chatbotservice.domain.model.RetrievedChunk;
import com.biddy.chatbotservice.infra.gemini.GeminiEmbeddingClient;
import com.biddy.chatbotservice.infra.persistence.DocumentChunkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 평가 질문 1건을 임베딩·검색해서, 정답 청크가 검색 결과에서 몇 번째 순위인지 찾는다.
 */
@Component
@RequiredArgsConstructor
public class RetrievalRankFinder {

    private final GeminiEmbeddingClient embeddingClient;
    private final DocumentChunkRepository documentChunkRepository;

    public Optional<Integer> findRank(EvalQuestion evalQuestion, int maxK) {
        float[] queryEmbedding = embeddingClient.embed(evalQuestion.question(), GeminiEmbeddingClient.TASK_TYPE_QUERY);
        List<RetrievedChunk> retrieved = documentChunkRepository.findSimilar(queryEmbedding, maxK);

        for (int i = 0; i < retrieved.size(); i++) {
            if (retrieved.get(i).content().equals(evalQuestion.expectedContent())) {
                return Optional.of(i + 1);
            }
        }
        return Optional.empty();
    }
}
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew :chatbot:test --tests "com.biddy.chatbotservice.application.RetrievalRankFinderTest"`
Expected: PASS (2 tests)

- [ ] **Step 5: 커밋**

```bash
git add chatbot/src/main/java/com/biddy/chatbotservice/application/RetrievalRankFinder.java chatbot/src/test/java/com/biddy/chatbotservice/application/RetrievalRankFinderTest.java
git commit -m "feat(chatbot): 질문 1건의 정답 청크 순위를 조회하는 RetrievalRankFinder 추가"
```

---

### Task 4: RetrievalAccuracyEvalService — 오케스트레이션

**Files:**
- Modify: `chatbot/src/main/java/com/biddy/chatbotservice/application/KnowledgeIngestionService.java`
- Create: `chatbot/src/main/java/com/biddy/chatbotservice/application/RetrievalAccuracyEvalService.java`
- Test: `chatbot/src/test/java/com/biddy/chatbotservice/application/RetrievalAccuracyEvalServiceTest.java`

**Interfaces:**
- Consumes: `KnowledgeIngestionService.loadChunks() -> List<DocumentChunk>` (기존 메서드, 이 Task에서 `private` → package-private으로 가시성만 변경), `EvalQuestionExtractor.extract` (Task 1), `RetrievalRankFinder.findRank` (Task 3), `RetrievalAccuracyCalculator.calculate` (Task 2)
- Produces: `RetrievalAccuracyEvalService.evaluate(List<Integer> kValues) -> RetrievalAccuracyReport`. Task 5(컨트롤러)가 이 시그니처를 그대로 사용한다.

- [ ] **Step 1: `KnowledgeIngestionService.loadChunks()` 가시성 변경**

`chatbot/src/main/java/com/biddy/chatbotservice/application/KnowledgeIngestionService.java`에서 아래 한 줄만 바꾼다 (다른 부분은 그대로).

변경 전:
```java
    private List<DocumentChunk> loadChunks() {
```

변경 후:
```java
    List<DocumentChunk> loadChunks() {
```

(같은 `application` 패키지 안에서 재사용하기 위한 가시성 변경. 클래스 밖에서는 여전히 접근 불가.)

- [ ] **Step 2: 실패하는 테스트 작성**

```java
package com.biddy.chatbotservice.application;

import com.biddy.chatbotservice.domain.model.DocumentChunk;
import com.biddy.chatbotservice.domain.model.EvalQuestion;
import com.biddy.chatbotservice.presentation.dto.RetrievalAccuracyReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class RetrievalAccuracyEvalServiceTest {

    @Mock private KnowledgeIngestionService knowledgeIngestionService;
    @Mock private EvalQuestionExtractor evalQuestionExtractor;
    @Mock private RetrievalRankFinder retrievalRankFinder;

    @InjectMocks
    private RetrievalAccuracyEvalService evalService;

    @Test
    void 청크_로드부터_정확도_계산까지_순서대로_호출해서_결과를_조립한다() {
        DocumentChunk chunk = new DocumentChunk("a.md", 0, "content");
        EvalQuestion evalQuestion = new EvalQuestion("질문", "a.md", "content");
        given(knowledgeIngestionService.loadChunks()).willReturn(List.of(chunk));
        given(evalQuestionExtractor.extract(List.of(chunk))).willReturn(List.of(evalQuestion));
        given(retrievalRankFinder.findRank(evalQuestion, 4)).willReturn(Optional.of(1));

        RetrievalAccuracyReport report = evalService.evaluate(List.of(1, 4));

        assertThat(report.totalQuestions()).isEqualTo(1);
        assertThat(report.results()).hasSize(2);
        assertThat(report.missedQuestions()).isEmpty();
    }

    @Test
    void kValues가_비어있으면_예외를_던진다() {
        assertThatThrownBy(() -> evalService.evaluate(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 3: 테스트 실행해서 실패(컴파일 에러) 확인**

Run: `./gradlew :chatbot:test --tests "com.biddy.chatbotservice.application.RetrievalAccuracyEvalServiceTest"`
Expected: FAIL — `RetrievalAccuracyEvalService` 클래스가 없어 컴파일 실패

- [ ] **Step 4: `RetrievalAccuracyEvalService` 작성**

```java
package com.biddy.chatbotservice.application;

import com.biddy.chatbotservice.domain.model.DocumentChunk;
import com.biddy.chatbotservice.domain.model.EvalQuestion;
import com.biddy.chatbotservice.domain.model.QuestionRankResult;
import com.biddy.chatbotservice.presentation.dto.RetrievalAccuracyReport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * knowledge 청크를 정답지 삼아 Top-K별 검색 정확도(Accuracy@K)를 평가한다.
 * self-retrieval(자기 질문으로 자기 청크 찾기) 방식이라 실제 사용자 질문보다
 * 낙관적인 상한선(upper bound) 추정치라는 점에 유의한다.
 */
@Service
@RequiredArgsConstructor
public class RetrievalAccuracyEvalService {

    private final KnowledgeIngestionService knowledgeIngestionService;
    private final EvalQuestionExtractor evalQuestionExtractor;
    private final RetrievalRankFinder retrievalRankFinder;

    public RetrievalAccuracyReport evaluate(List<Integer> kValues) {
        int maxK = kValues.stream().max(Integer::compareTo)
                .orElseThrow(() -> new IllegalArgumentException("topKs 파라미터가 비어 있습니다."));

        List<DocumentChunk> chunks = knowledgeIngestionService.loadChunks();
        List<EvalQuestion> evalQuestions = evalQuestionExtractor.extract(chunks);

        List<QuestionRankResult> rankResults = evalQuestions.stream()
                .map(q -> new QuestionRankResult(q.question(), retrievalRankFinder.findRank(q, maxK)))
                .toList();

        return RetrievalAccuracyCalculator.calculate(rankResults, kValues);
    }
}
```

- [ ] **Step 5: 테스트 실행해서 통과 확인**

Run: `./gradlew :chatbot:test --tests "com.biddy.chatbotservice.application.RetrievalAccuracyEvalServiceTest"`
Expected: PASS (2 tests)

- [ ] **Step 6: 커밋**

```bash
git add chatbot/src/main/java/com/biddy/chatbotservice/application/KnowledgeIngestionService.java chatbot/src/main/java/com/biddy/chatbotservice/application/RetrievalAccuracyEvalService.java chatbot/src/test/java/com/biddy/chatbotservice/application/RetrievalAccuracyEvalServiceTest.java
git commit -m "feat(chatbot): 평가 파이프라인을 오케스트레이션하는 RetrievalAccuracyEvalService 추가"
```

---

### Task 5: ChatbotController — REST 엔드포인트 노출

이 Task는 기존 프로젝트 컨벤션에 맞춰 컨트롤러에 위임 메서드 하나만 추가한다. 기존 `ChatbotController`에도 단위 테스트가 없어(기존 프로젝트 테스트 커버리지 수준과 동일한 선에서), 별도 컨트롤러 테스트는 만들지 않는다 — 로직은 이미 Task 1~4에서 전부 단위 테스트로 검증됐다.

**Files:**
- Modify: `chatbot/src/main/java/com/biddy/chatbotservice/presentation/controller/ChatbotController.java`

**Interfaces:**
- Consumes: `RetrievalAccuracyEvalService.evaluate(List<Integer>) -> RetrievalAccuracyReport` (Task 4)

- [ ] **Step 1: import 추가**

기존 `ChatbotController`는 `PostMapping`을 이미 import하고 있다. 아래 4개만 새로 추가한다.

```java
import com.biddy.chatbotservice.application.RetrievalAccuracyEvalService;
import com.biddy.chatbotservice.presentation.dto.RetrievalAccuracyReport;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
```

- [ ] **Step 2: 필드 추가**

기존 `private final KnowledgeIngestionService knowledgeIngestionService;` 바로 아래에 추가:
```java
    private final RetrievalAccuracyEvalService retrievalAccuracyEvalService;
```

- [ ] **Step 3: 엔드포인트 메서드 추가**

`reingest()` 메서드 바로 아래에 추가:
```java
    @Operation(
            summary = "검색 정확도(Top-K) 평가",
            description = "knowledge 청크 자신의 질문으로 Top-K별 검색 정확도(Recall@K)를 측정한다. " +
                    "자기 질문 기반 self-retrieval이라 실제 사용자 질문보다 낙관적인 상한선 추정치다."
    )
    @PostMapping("/admin/eval/retrieval-accuracy")
    public RetrievalAccuracyReport evaluateRetrievalAccuracy(
            @RequestParam(defaultValue = "1,2,4,8") List<Integer> topKs) {
        return retrievalAccuracyEvalService.evaluate(topKs);
    }
```

- [ ] **Step 4: chatbot 모듈 컴파일 확인**

Run: `./gradlew :chatbot:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add chatbot/src/main/java/com/biddy/chatbotservice/presentation/controller/ChatbotController.java
git commit -m "feat(chatbot): 검색 정확도 평가 REST 엔드포인트 추가"
```

---

### Task 6: 전체 검증

**Files:** 없음 (검증 전용)

- [ ] **Step 1: chatbot 모듈 전체 테스트 실행**

Run: `./gradlew :chatbot:test`
Expected: PASS — Task 1~4에서 추가한 테스트(EvalQuestionExtractorTest 3개, RetrievalAccuracyCalculatorTest 4개, RetrievalRankFinderTest 2개, RetrievalAccuracyEvalServiceTest 2개 = 총 11개) 모두 그린. (chatbot 모듈에는 이번이 첫 테스트라 DB 의존 테스트로 인한 실패는 없을 것으로 예상 — 만약 다른 실패가 있다면 원인을 확인하고 보고한다.)

- [ ] **Step 2: chatbot 모듈 빌드 확인**

Run: `./gradlew :chatbot:build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: README "진행 중인 개선 작업" 섹션 갱신**

`README.md`의 "## 9. 진행 중인 개선 작업" 중 RAG 챗봇 환각률 검증 항목을 "예정"에서 아래 내용으로 갱신한다 (검색 정확도까지 구현 완료, 실제 수치는 아직 미측정임을 명확히 구분해서 적을 것):

```
- **RAG 챗봇 검색 정확도(Top-K) 평가 도구 — 구현 완료, 실측은 예정.** knowledge 청크 자신의 질문을 정답지 삼아 Top-K별 검색 정확도(Recall@K)를 계산하는 기능을 추가했습니다(`/api/chatbot/admin/eval/retrieval-accuracy`). self-retrieval 기반이라 실제 사용자 질문보다 낙관적인 상한선 추정치이며, 실제 수치는 로컬 환경(Docker + GEMINI_API_KEY)에서 직접 실행해 확인할 계획입니다. 단위 테스트 11개(질문 추출, Top-K별 정확도 계산, 순위 조회, 파이프라인 조립) 전부 통과 확인. 설계 문서: [`docs/superpowers/specs/2026-09-04-rag-retrieval-accuracy-eval-design.md`](./docs/superpowers/specs/2026-09-04-rag-retrieval-accuracy-eval-design.md) · 구현 계획: [`docs/superpowers/plans/2026-09-04-rag-retrieval-accuracy-eval.md`](./docs/superpowers/plans/2026-09-04-rag-retrieval-accuracy-eval.md)
```

- [ ] **Step 4: 최종 커밋 & 개인 저장소 push**

```bash
git add README.md
git commit -m "docs: RAG 검색 정확도 평가 도구 구현 완료 상태로 README 갱신"
git push origin develop
```
