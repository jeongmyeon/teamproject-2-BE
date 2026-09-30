# Paraphrase 기반 RAG 검색 정확도 평가 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 기존 self-retrieval 평가 파이프라인을 재사용해, Gemini로 생성한 paraphrase(다르게 표현한 질문)에 대한 Top-K 검색 정확도를 평가하는 새 엔드포인트를 추가한다.

**Architecture:** `ParaphraseGenerator`가 원본 `EvalQuestion` 목록을 받아 Gemini로 질문당 N개의 paraphrase를 생성해 같은 정답 기준(`expectedSource`/`expectedContent`)을 가진 새 `EvalQuestion` 목록으로 변환한다. `ParaphrasedRetrievalAccuracyEvalService`가 기존 `EvalQuestionExtractor`·`RetrievalRankFinder`·`RetrievalAccuracyCalculator`와 이 신규 컴포넌트를 조합한다.

**Tech Stack:** Spring Boot, JUnit 5 + Mockito + AssertJ, 참조 스펙: [`docs/superpowers/specs/2026-10-01-paraphrased-retrieval-eval-design.md`](../specs/2026-10-01-paraphrased-retrieval-eval-design.md)

## Global Constraints

- paraphrase의 정답 판정 기준은 원본 질문과 동일하다 — `expectedSource`/`expectedContent`를 그대로 물려받는다.
- Gemini 응답 파싱은 방어적으로 처리한다 — 요청한 개수보다 적게 와도 예외를 던지지 않고 있는 만큼만 사용한다.
- 기존 `/api/chatbot/admin/eval/retrieval-accuracy` 엔드포인트와 그 하위 컴포넌트는 변경하지 않는다 — 새 엔드포인트·새 컴포넌트만 추가한다.
- `chatbot` 모듈 테스트는 DB·Gemini API 키 없이도 전부 단위 테스트로 통과해야 한다 (Mockito로 외부 의존성 대체).

---

### Task 1: ParaphraseGenerator — Gemini 응답 파싱 (순수 함수)

**Files:**
- Create: `chatbot/src/main/java/com/biddy/chatbotservice/application/ParaphraseGenerator.java`
- Test: `chatbot/src/test/java/com/biddy/chatbotservice/application/ParaphraseGeneratorTest.java`

**Interfaces:**
- Consumes: 없음 (이 Task는 순수 파싱 로직만 다룬다)
- Produces: `ParaphraseGenerator.parseLines(String response, int count) -> List<String>` (package-private). Task 2에서 이 메서드를 내부적으로 사용한다.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.biddy.chatbotservice.application;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ParaphraseGeneratorTest {

    private final ParaphraseGenerator generator = new ParaphraseGenerator(null);

    @Test
    void 줄바꿈으로_구분된_응답에서_요청한_개수만큼_추출한다() {
        String response = "가입은 어떻게 해요?\n회원 등록 절차가 궁금해요.";

        List<String> result = generator.parseLines(response, 2);

        assertThat(result).containsExactly("가입은 어떻게 해요?", "회원 등록 절차가 궁금해요.");
    }

    @Test
    void 번호와_불릿과_따옴표가_섞여도_깨끗하게_파싱한다() {
        String response = "1. \"가입은 어떻게 해요?\"\n- 회원 등록 절차가 궁금해요.";

        List<String> result = generator.parseLines(response, 2);

        assertThat(result).containsExactly("가입은 어떻게 해요?\"", "회원 등록 절차가 궁금해요.");
    }

    @Test
    void 빈_줄은_건너뛴다() {
        String response = "가입은 어떻게 해요?\n\n\n회원 등록 절차가 궁금해요.";

        List<String> result = generator.parseLines(response, 2);

        assertThat(result).containsExactly("가입은 어떻게 해요?", "회원 등록 절차가 궁금해요.");
    }

    @Test
    void 요청한_개수보다_적게_반환되면_있는_만큼만_반환한다() {
        String response = "가입은 어떻게 해요?";

        List<String> result = generator.parseLines(response, 2);

        assertThat(result).containsExactly("가입은 어떻게 해요?");
    }

    @Test
    void 응답이_null이면_빈_리스트를_반환한다() {
        List<String> result = generator.parseLines(null, 2);

        assertThat(result).isEmpty();
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패(컴파일 에러) 확인**

Run: `./gradlew :chatbot:test --tests "com.biddy.chatbotservice.application.ParaphraseGeneratorTest"`
Expected: FAIL — `ParaphraseGenerator` 클래스가 없어 컴파일 실패

- [ ] **Step 3: `ParaphraseGenerator` 기본 골격 + `parseLines` 작성**

```java
package com.biddy.chatbotservice.application;

import com.biddy.chatbotservice.infra.gemini.GeminiGenerationClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 원본 평가 질문을 Gemini로 paraphrase(다르게 표현한 질문)해 같은 정답 기준을 갖는
 * 새 평가 질문 목록으로 변환한다.
 */
@Component
@RequiredArgsConstructor
public class ParaphraseGenerator {

    private final GeminiGenerationClient generationClient;

    List<String> parseLines(String response, int count) {
        if (response == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String raw : response.split("\\r?\\n")) {
            String cleaned = raw.replaceAll("^[\\s\\-*\\d.)\"'“”]+", "").trim();
            if (cleaned.isEmpty()) {
                continue;
            }
            lines.add(cleaned);
            if (lines.size() >= count) {
                break;
            }
        }
        return lines;
    }
}
```

**참고**: 테스트 2번째 케이스(`번호와_불릿과_따옴표가_섞여도`)는 줄 **앞쪽**의 번호/불릿/따옴표만 제거하는 정규식이므로, 줄 끝의 닫는 따옴표(`"`)는 그대로 남는다 — 테스트의 기대값도 그에 맞춰 작성되어 있다(`"가입은 어떻게 해요?\""`).

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew :chatbot:test --tests "com.biddy.chatbotservice.application.ParaphraseGeneratorTest"`
Expected: PASS (5 tests)

- [ ] **Step 5: 커밋**

```bash
git add chatbot/src/main/java/com/biddy/chatbotservice/application/ParaphraseGenerator.java chatbot/src/test/java/com/biddy/chatbotservice/application/ParaphraseGeneratorTest.java
git commit -m "feat(chatbot): Gemini paraphrase 응답을 파싱하는 ParaphraseGenerator 뼈대 추가"
```

---

### Task 2: ParaphraseGenerator — Gemini 호출 및 EvalQuestion 변환

**Files:**
- Modify: `chatbot/src/main/java/com/biddy/chatbotservice/application/ParaphraseGenerator.java`
- Modify: `chatbot/src/test/java/com/biddy/chatbotservice/application/ParaphraseGeneratorTest.java`

**Interfaces:**
- Consumes: `GeminiGenerationClient.generate(String) -> String` (기존), `EvalQuestion` (기존, Task1 dir `domain/model`)
- Produces: `ParaphraseGenerator.generateParaphrasedQuestions(List<EvalQuestion>, int) -> List<EvalQuestion>`. Task 3(`ParaphrasedRetrievalAccuracyEvalService`)가 이 시그니처를 그대로 사용한다.

- [ ] **Step 1: 실패하는 테스트 추가**

기존 `ParaphraseGeneratorTest`에 아래 import와 테스트를 추가한다.

```java
import com.biddy.chatbotservice.domain.model.EvalQuestion;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.BDDMockito.given;
```

클래스 선언에 `@ExtendWith(MockitoExtension.class)`를 추가하고, `private final ParaphraseGenerator generator = new ParaphraseGenerator(null);` 필드를 아래로 교체한다.

```java
    @Mock
    private GeminiGenerationClient generationClient;

    private ParaphraseGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new ParaphraseGenerator(generationClient);
    }
```

(`import org.junit.jupiter.api.BeforeEach;`도 추가한다.)

새 테스트 메서드를 추가한다.

```java
    @Test
    void 원본_질문마다_요청한_개수만큼_paraphrase를_생성해서_같은_정답기준의_EvalQuestion으로_변환한다() {
        EvalQuestion original = new EvalQuestion("회원가입은 어떻게 하나요?", "01_회원.md", "원본 청크 내용");
        given(generationClient.generate(org.mockito.ArgumentMatchers.anyString()))
                .willReturn("가입하려면 뭐부터 해야 하나요?\n회원 등록 절차가 궁금해요.");

        List<EvalQuestion> result = generator.generateParaphrasedQuestions(List.of(original), 2);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).question()).isEqualTo("가입하려면 뭐부터 해야 하나요?");
        assertThat(result.get(0).expectedSource()).isEqualTo("01_회원.md");
        assertThat(result.get(0).expectedContent()).isEqualTo("원본 청크 내용");
        assertThat(result.get(1).question()).isEqualTo("회원 등록 절차가 궁금해요.");
        assertThat(result.get(1).expectedSource()).isEqualTo("01_회원.md");
    }

    @Test
    void 원본_질문이_여러_개면_각각에_대해_paraphrase를_생성해_모두_합친다() {
        EvalQuestion q1 = new EvalQuestion("질문1", "a.md", "청크1");
        EvalQuestion q2 = new EvalQuestion("질문2", "b.md", "청크2");
        given(generationClient.generate(org.mockito.ArgumentMatchers.anyString()))
                .willReturn("변형1\n변형2");

        List<EvalQuestion> result = generator.generateParaphrasedQuestions(List.of(q1, q2), 2);

        assertThat(result).hasSize(4);
        assertThat(result).extracting(EvalQuestion::expectedSource)
                .containsExactly("a.md", "a.md", "b.md", "b.md");
    }
```

- [ ] **Step 2: 테스트 실행해서 실패(컴파일 에러) 확인**

Run: `./gradlew :chatbot:test --tests "com.biddy.chatbotservice.application.ParaphraseGeneratorTest"`
Expected: FAIL — `generateParaphrasedQuestions` 메서드가 없어 컴파일 실패

- [ ] **Step 3: `ParaphraseGenerator`에 Gemini 호출·변환 로직 추가**

`ParaphraseGenerator.java`에 아래 메서드들을 추가한다 (`parseLines`는 그대로 유지).

```java
    public List<EvalQuestion> generateParaphrasedQuestions(List<EvalQuestion> originalQuestions, int perQuestion) {
        List<EvalQuestion> result = new ArrayList<>();
        for (EvalQuestion original : originalQuestions) {
            List<String> paraphrases = paraphrase(original.question(), perQuestion);
            for (String paraphrased : paraphrases) {
                result.add(new EvalQuestion(paraphrased, original.expectedSource(), original.expectedContent()));
            }
        }
        return result;
    }

    private List<String> paraphrase(String question, int count) {
        String prompt = buildPrompt(question, count);
        String response = generationClient.generate(prompt);
        return parseLines(response, count);
    }

    private String buildPrompt(String question, int count) {
        return """
                아래 질문을 실제 사용자가 챗봇에게 물어볼 법한 자연스러운 다른 표현으로 %d개 바꿔 써줘.
                원래 의미는 그대로 유지하되, 표현과 어순은 다르게 해줘.
                각 결과는 한 줄에 하나씩, 번호나 설명 없이 질문 문장만 출력해줘.

                질문: %s
                """.formatted(count, question);
    }
```

필요한 import를 추가한다: `com.biddy.chatbotservice.domain.model.EvalQuestion`.

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew :chatbot:test --tests "com.biddy.chatbotservice.application.ParaphraseGeneratorTest"`
Expected: PASS (7 tests)

- [ ] **Step 5: 커밋**

```bash
git add chatbot/src/main/java/com/biddy/chatbotservice/application/ParaphraseGenerator.java chatbot/src/test/java/com/biddy/chatbotservice/application/ParaphraseGeneratorTest.java
git commit -m "feat(chatbot): ParaphraseGenerator가 Gemini로 paraphrase를 생성하도록 구현"
```

---

### Task 3: ParaphrasedRetrievalAccuracyEvalService — 오케스트레이션

**Files:**
- Create: `chatbot/src/main/java/com/biddy/chatbotservice/application/ParaphrasedRetrievalAccuracyEvalService.java`
- Test: `chatbot/src/test/java/com/biddy/chatbotservice/application/ParaphrasedRetrievalAccuracyEvalServiceTest.java`

**Interfaces:**
- Consumes: `KnowledgeIngestionService.loadChunks()`, `EvalQuestionExtractor.extract()`, `ParaphraseGenerator.generateParaphrasedQuestions()` (Task 2), `RetrievalRankFinder.findRank()`, `RetrievalAccuracyCalculator.calculate()` (모두 기존)
- Produces: `ParaphrasedRetrievalAccuracyEvalService.evaluate(List<Integer> kValues, int paraphrasesPerQuestion) -> RetrievalAccuracyReport`. Task 4(컨트롤러)가 이 시그니처를 그대로 사용한다.

- [ ] **Step 1: 실패하는 테스트 작성**

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
class ParaphrasedRetrievalAccuracyEvalServiceTest {

    @Mock private KnowledgeIngestionService knowledgeIngestionService;
    @Mock private EvalQuestionExtractor evalQuestionExtractor;
    @Mock private ParaphraseGenerator paraphraseGenerator;
    @Mock private RetrievalRankFinder retrievalRankFinder;

    @InjectMocks
    private ParaphrasedRetrievalAccuracyEvalService evalService;

    @Test
    void 청크_로드부터_paraphrase_생성_정확도_계산까지_순서대로_호출해서_결과를_조립한다() {
        DocumentChunk chunk = new DocumentChunk("a.md", 0, "content");
        EvalQuestion original = new EvalQuestion("원본 질문", "a.md", "content");
        EvalQuestion paraphrased = new EvalQuestion("바뀐 질문", "a.md", "content");
        given(knowledgeIngestionService.loadChunks()).willReturn(List.of(chunk));
        given(evalQuestionExtractor.extract(List.of(chunk))).willReturn(List.of(original));
        given(paraphraseGenerator.generateParaphrasedQuestions(List.of(original), 2))
                .willReturn(List.of(paraphrased, paraphrased));
        given(retrievalRankFinder.findRank(paraphrased, 4)).willReturn(Optional.of(1));

        RetrievalAccuracyReport report = evalService.evaluate(List.of(1, 4), 2);

        assertThat(report.totalQuestions()).isEqualTo(2);
        assertThat(report.results()).hasSize(2);
        assertThat(report.missedQuestions()).isEmpty();
    }

    @Test
    void kValues가_비어있으면_예외를_던진다() {
        assertThatThrownBy(() -> evalService.evaluate(List.of(), 2))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패(컴파일 에러) 확인**

Run: `./gradlew :chatbot:test --tests "com.biddy.chatbotservice.application.ParaphrasedRetrievalAccuracyEvalServiceTest"`
Expected: FAIL — `ParaphrasedRetrievalAccuracyEvalService` 클래스가 없어 컴파일 실패

- [ ] **Step 3: `ParaphrasedRetrievalAccuracyEvalService` 작성**

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
 * Gemini로 생성한 paraphrase(다르게 표현한 질문) 질의셋으로 Top-K별 검색 정확도를 평가한다.
 * self-retrieval 평가(RetrievalAccuracyEvalService)보다 실제 사용자 질문에 가까운
 * 더 엄격한 평가 기준을 제공한다.
 */
@Service
@RequiredArgsConstructor
public class ParaphrasedRetrievalAccuracyEvalService {

    private final KnowledgeIngestionService knowledgeIngestionService;
    private final EvalQuestionExtractor evalQuestionExtractor;
    private final ParaphraseGenerator paraphraseGenerator;
    private final RetrievalRankFinder retrievalRankFinder;

    public RetrievalAccuracyReport evaluate(List<Integer> kValues, int paraphrasesPerQuestion) {
        int maxK = kValues.stream().max(Integer::compareTo)
                .orElseThrow(() -> new IllegalArgumentException("topKs 파라미터가 비어 있습니다."));

        List<DocumentChunk> chunks = knowledgeIngestionService.loadChunks();
        List<EvalQuestion> originalQuestions = evalQuestionExtractor.extract(chunks);
        List<EvalQuestion> paraphrasedQuestions =
                paraphraseGenerator.generateParaphrasedQuestions(originalQuestions, paraphrasesPerQuestion);

        List<QuestionRankResult> rankResults = paraphrasedQuestions.stream()
                .map(q -> new QuestionRankResult(q.question(), retrievalRankFinder.findRank(q, maxK)))
                .toList();

        return RetrievalAccuracyCalculator.calculate(rankResults, kValues);
    }
}
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew :chatbot:test --tests "com.biddy.chatbotservice.application.ParaphrasedRetrievalAccuracyEvalServiceTest"`
Expected: PASS (2 tests)

- [ ] **Step 5: 커밋**

```bash
git add chatbot/src/main/java/com/biddy/chatbotservice/application/ParaphrasedRetrievalAccuracyEvalService.java chatbot/src/test/java/com/biddy/chatbotservice/application/ParaphrasedRetrievalAccuracyEvalServiceTest.java
git commit -m "feat(chatbot): paraphrase 평가 파이프라인을 오케스트레이션하는 ParaphrasedRetrievalAccuracyEvalService 추가"
```

---

### Task 4: ChatbotController — REST 엔드포인트 노출

**Files:**
- Modify: `chatbot/src/main/java/com/biddy/chatbotservice/presentation/controller/ChatbotController.java`

**Interfaces:**
- Consumes: `ParaphrasedRetrievalAccuracyEvalService.evaluate(List<Integer>, int) -> RetrievalAccuracyReport` (Task 3)

기존 컨트롤러 테스트 부재 컨벤션을 따라 별도 컨트롤러 테스트는 작성하지 않는다 (Task 1~3에서 로직은 이미 전부 단위 테스트로 검증됨).

- [ ] **Step 1: import·필드 추가**

기존 `import com.biddy.chatbotservice.application.RetrievalAccuracyEvalService;` 바로 아래에 추가:
```java
import com.biddy.chatbotservice.application.ParaphrasedRetrievalAccuracyEvalService;
```

기존 `private final RetrievalAccuracyEvalService retrievalAccuracyEvalService;` 바로 아래에 추가:
```java
    private final ParaphrasedRetrievalAccuracyEvalService paraphrasedRetrievalAccuracyEvalService;
```

- [ ] **Step 2: 엔드포인트 메서드 추가**

`evaluateRetrievalAccuracy(...)` 메서드 바로 아래에 추가:

```java
    @Operation(
            summary = "Paraphrase 기반 검색 정확도 평가",
            description = "FAQ 원본 질문을 Gemini로 paraphrase(다르게 표현)한 뒤, 그 질문들로 Top-K별 검색 정확도를 측정한다. " +
                    "self-retrieval 평가보다 실제 사용자 질문에 가까운 더 엄격한 기준이다."
    )
    @PostMapping("/admin/eval/retrieval-accuracy-paraphrased")
    public RetrievalAccuracyReport evaluateParaphrasedRetrievalAccuracy(
            @RequestParam(defaultValue = "1,2,4,8") List<Integer> topKs,
            @RequestParam(defaultValue = "2") int paraphrasesPerQuestion) {
        return paraphrasedRetrievalAccuracyEvalService.evaluate(topKs, paraphrasesPerQuestion);
    }
```

- [ ] **Step 3: chatbot 모듈 컴파일 확인**

Run: `./gradlew :chatbot:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```bash
git add chatbot/src/main/java/com/biddy/chatbotservice/presentation/controller/ChatbotController.java
git commit -m "feat(chatbot): paraphrase 기반 검색 정확도 평가 REST 엔드포인트 추가"
```

---

### Task 5: 전체 검증

**Files:** 없음 (검증 전용)

- [ ] **Step 1: chatbot 모듈 전체 테스트 실행**

Run: `./gradlew :chatbot:test`
Expected: PASS — 기존 11개 + 이번에 추가한 테스트(ParaphraseGeneratorTest 7개, ParaphrasedRetrievalAccuracyEvalServiceTest 2개 = 9개) = 총 20개 전부 그린

- [ ] **Step 2: chatbot 모듈 빌드 확인**

Run: `./gradlew :chatbot:build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: README "진행 중인 개선 작업" 섹션 갱신**

`README.md`의 RAG 챗봇 검색 정확도 평가 항목에, paraphrase 기반 평가가 추가로 구현됐다는 내용과 실측 결과(실행 후 확인)를 반영한다. 정확한 문구는 구현 완료 시점에 작성한다.

- [ ] **Step 4: 최종 커밋 & 개인 저장소 push**

```bash
git add README.md
git commit -m "docs: paraphrase 기반 RAG 검색 정확도 평가 구현 완료 상태로 README 갱신"
git push origin develop
```
