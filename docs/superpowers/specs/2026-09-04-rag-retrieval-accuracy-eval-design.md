# RAG 검색 정확도(Top-K) 평가 도구 설계

- 작성일: 2026-09-04
- 대상 모듈: `chatbot` (chatbotservice)
- 관련 파일: `ChatbotQueryService`, `KnowledgeIngestionService`, `DocumentChunkRepository`, `GeminiEmbeddingClient`, `ChatbotController`

## 배경 / 현재 문제

`ChatbotQueryService`는 사용자 질문을 임베딩해 pgvector로 상위 `chatbot.rag.top-k`(기본 4)개 청크를 검색하고, 그 청크만 근거로 Gemini가 답변을 생성한다. "참고 문서에 없으면 답하지 않는다"는 프롬프트 제약으로 환각을 줄였다고 판단했지만, **이 Top-K 검색이 실제로 정답 청크를 얼마나 잘 찾아내는지 수치로 확인한 적이 없다.** README "8. 향후 개선점"에 이미 명시된 한계다.

## 목표

- `knowledge/*.md`에 이미 있는 29개 Q&A 청크를 정답지 삼아, **Top-K 값별 검색 정확도(Accuracy@K)**를 측정하는 기능을 추가한다.
- 실제 값은 이 세션(DB·Gemini API 키 없음)에서는 얻을 수 없으므로, **도구 자체를 코드로 완성하고 순수 계산 로직은 이 세션에서 단위 테스트로 검증**한다. 실제 수치는 사용자가 로컬(도커 + `GEMINI_API_KEY`)에서 직접 실행해 얻는다.
- 기존 `/api/chatbot/admin/reingest`와 같은 패턴으로 관리자용 REST 엔드포인트를 추가해, Swagger UI에서 바로 실행·확인 가능하게 한다.

## 비목표 (Out of scope)

- **생성 답변의 근거성(그라운딩) 자동 판정(LLM-as-judge)** — 검색 정확도(Recall@K)까지만 다룬다. 답변 자체가 환각인지 판정하려면 추가 Gemini 호출(비용·신뢰도 문제)이 필요해 범위에서 제외한다.
- **사람이 다시 표현한(paraphrased) 질문 데이터셋 구축** — 이번 범위는 기존 29개 FAQ 질문을 그대로 재사용하는 self-retrieval 평가다. 별도 질의셋 파일을 새로 작성하지 않는다.
- **평가 결과의 영속화(DB 저장, 이력 관리)** — 매 호출마다 즉시 계산해서 응답으로만 반환한다.

## 질의셋 구성 — self-retrieval

별도 질의셋을 새로 만들지 않고, `KnowledgeIngestionService`가 이미 `knowledge/*.md`를 "## Q: ..." 단위로 쪼갠 29개 청크를 그대로 재사용한다.

- 각 청크의 content는 `"{문서 제목}\n\n## Q: {질문}\nA: {답변}"` 형태다.
- 정규식 `^## Q: (.+)$` (MULTILINE)로 질문 텍스트만 추출한다.
- "이 질문을 챗봇에 물으면, 검색 결과 안에 **이 청크 자신**(content 완전 일치)이 몇 번째 순위로 나오는가"를 측정한다.

**알려진 한계**: 이건 "자기 질문으로 자기 청크를 찾는" self-retrieval이라, 실제 사용자가 다르게 표현한 질문보다 낙관적인 **상한선(upper bound) 추정치**다. README에도 이 한계를 명시한다.

## 아키텍처

```
ChatbotController
  └─ POST /api/chatbot/admin/eval/retrieval-accuracy?topKs=1,2,4,8
       └─ RetrievalAccuracyEvalService.evaluate(List<Integer> kValues)
            ├─ KnowledgeIngestionService.loadChunks()        (기존 private → package-private로 재사용)
            ├─ EvalQuestionExtractor.extract(chunks)          → List<EvalQuestion>   [순수, 단위 테스트 가능]
            ├─ RetrievalRankFinder.findRank(q, maxK)          → Optional<Integer>    [Mockito로 단위 테스트]
            └─ RetrievalAccuracyCalculator.calculate(results, kValues) → RetrievalAccuracyReport  [순수, 단위 테스트 가능]
```

### 신규/변경 컴포넌트

| 컴포넌트 | 위치 | 역할 |
| --- | --- | --- |
| `EvalQuestion` (record) | `domain/model` | `(question, expectedSource, expectedContent)` |
| `QuestionRankResult` (record) | `domain/model` | `(question, Optional<Integer> rank)` — 평가 1건의 결과 |
| `EvalQuestionExtractor` | `application` | 청크 목록 → `EvalQuestion` 목록. 정규식으로 질문만 추출, 패턴 없는 청크는 건너뜀 |
| `RetrievalRankFinder` | `application` | 질문 1건을 임베딩·검색해 정답 청크의 순위를 찾음 (`GeminiEmbeddingClient`, `DocumentChunkRepository` 사용) |
| `RetrievalAccuracyCalculator` | `application` | **순수 함수.** 순위 목록 + K값 목록 → `RetrievalAccuracyReport` |
| `RetrievalAccuracyEvalService` | `application` | 위 컴포넌트들을 순서대로 호출하는 오케스트레이터 |
| `RetrievalAccuracyReport` (record) | `presentation/dto` | 응답 DTO. `(totalQuestions, List<TopKAccuracy>, missedQuestions)`, `TopKAccuracy = (k, hitCount, accuracy)` |
| `KnowledgeIngestionService.loadChunks()` | `application` (기존 파일 수정) | `private` → package-private(수정자 없음)으로 가시성만 변경해 재사용 |
| `ChatbotController` | `presentation/controller` (기존 파일 수정) | `POST /api/chatbot/admin/eval/retrieval-accuracy` 엔드포인트 추가 |

### 핵심 로직

**`RetrievalRankFinder.findRank`**: 질문을 `GeminiEmbeddingClient.TASK_TYPE_QUERY`로 임베딩 → `DocumentChunkRepository.findSimilar(embedding, maxK)`로 상위 `maxK`개 조회 → 반환된 리스트를 순서대로 훑어 `content()`가 `evalQuestion.expectedContent()`와 완전히 같은 항목을 찾으면 그 위치(1부터 시작)를 반환, 없으면 `Optional.empty()`.

**`RetrievalAccuracyCalculator.calculate`**: 각 K값에 대해 `hitCount = rank가 존재하고 rank <= K인 질문 수`, `accuracy = hitCount / totalQuestions`. `kValues`가 비어 있으면 `IllegalArgumentException`. 가장 큰 K로도 못 찾은 질문들은 `missedQuestions`로 반환(어떤 FAQ가 특히 검색이 안 되는지 바로 보여주기 위함).

### REST 엔드포인트

```
POST /api/chatbot/admin/eval/retrieval-accuracy?topKs=1,2,4,8
```

- `topKs`: 콤마로 구분된 정수 목록, 기본값 `1,2,4,8` (Spring이 `List<Integer>`로 자동 바인딩)
- 응답: `RetrievalAccuracyReport`

```json
{
  "totalQuestions": 29,
  "results": [
    { "k": 1, "hitCount": 20, "accuracy": 0.69 },
    { "k": 2, "hitCount": 24, "accuracy": 0.83 },
    { "k": 4, "hitCount": 27, "accuracy": 0.93 },
    { "k": 8, "hitCount": 29, "accuracy": 1.0 }
  ],
  "missedQuestions": []
}
```

기존 `/api/chatbot/admin/reingest`와 동일하게 별도 인증 어노테이션 없이 컨트롤러에 추가한다 (Gateway의 `/admin/**` 경로 규칙으로 이미 보호되는 기존 컨벤션을 따름).

## 테스트 계획 (TDD)

DB·Gemini API 없이도 아래는 전부 단위 테스트로 검증 가능하다.

1. `EvalQuestionExtractorTest` — 샘플 청크 content 문자열에서 질문을 정확히 추출하는지, "## Q:" 패턴이 없는 청크는 건너뛰는지
2. `RetrievalAccuracyCalculatorTest` (순수 함수, 가장 핵심) —
   - 모든 질문이 K=1 안에서 정답이면 accuracy 1.0
   - 일부만 K=1에서 맞고 K=4에서는 다 맞는 경우 K별로 다른 accuracy가 나오는지
   - 가장 큰 K로도 못 찾은 질문이 `missedQuestions`에 포함되는지
   - `kValues`가 비어 있으면 `IllegalArgumentException`
3. `RetrievalRankFinderTest` (Mockito) — `GeminiEmbeddingClient`, `DocumentChunkRepository`를 mock으로 대체해, 정답 청크가 2번째 순위에 있는 가짜 검색 결과를 주면 `Optional.of(2)`를 반환하는지, 못 찾으면 `Optional.empty()`인지
4. `RetrievalAccuracyEvalServiceTest` (Mockito) — 하위 컴포넌트를 모두 mock으로 대체해 올바른 순서로 호출되고 결과가 잘 조립되는지 (배선 검증)

## 마이그레이션/실행 참고

- 스키마 변경 없음 (기존 `document_chunk` 테이블 그대로 사용).
- 실제 수치를 얻으려면: `docker-compose up -d`로 DB·chatbot-service 기동 → `knowledge` 문서가 이미 적재돼 있는지 확인(안 되어 있으면 `/api/chatbot/admin/reingest` 먼저 호출) → `/api/chatbot/admin/eval/retrieval-accuracy` 호출.
