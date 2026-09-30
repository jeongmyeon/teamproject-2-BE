# Paraphrase 기반 RAG 검색 정확도 평가 설계

- 작성일: 2026-10-01
- 대상 모듈: `chatbot` (chatbotservice)
- 전제: [`2026-09-04-rag-retrieval-accuracy-eval-design.md`](./2026-09-04-rag-retrieval-accuracy-eval-design.md)에서 구현한 self-retrieval 평가(`/api/chatbot/admin/eval/retrieval-accuracy`)가 이미 존재함

## 배경

self-retrieval 평가(FAQ 자기 질문으로 자기 청크 찾기)는 실제 실행 결과 K=1부터 100% 정확도가 나왔다. 이는 파이프라인이 정상 동작함을 증명했지만, "실제 사용자가 다르게 표현한 질문에도 잘 검색되는가"는 검증하지 못했다 — 포트폴리오에 이미 이 한계를 다음 단계 과제로 명시해뒀다.

## 목표

- 기존 29개 FAQ 질문 각각에 대해 Gemini로 자연스러운 paraphrase를 N개(기본 2개) 생성한다.
- paraphrase된 질문으로 기존과 동일한 검색 파이프라인(`RetrievalRankFinder`, `RetrievalAccuracyCalculator`)을 재사용해 Top-K별 정확도를 계산한다.
- 기존 self-retrieval 엔드포인트는 그대로 두고, **새 엔드포인트**로 독립적으로 제공해 두 결과를 비교할 수 있게 한다.

## 비목표

- paraphrase 품질의 사람 검수(여전히 Gemini가 생성한 결과를 그대로 신뢰) — 이 자체가 다음 단계의 한계로 문서화될 수 있음.
- paraphrase 결과의 캐싱/영속화 — 매 호출마다 새로 생성한다 (기존 평가와 동일한 패턴).

## 아키텍처

```
ChatbotController
  └─ POST /api/chatbot/admin/eval/retrieval-accuracy-paraphrased?topKs=1,2,4,8&paraphrasesPerQuestion=2
       └─ ParaphrasedRetrievalAccuracyEvalService.evaluate(kValues, paraphrasesPerQuestion)
            ├─ KnowledgeIngestionService.loadChunks()                      (기존 재사용)
            ├─ EvalQuestionExtractor.extract(chunks)                       (기존 재사용) → List<EvalQuestion>
            ├─ ParaphraseGenerator.generateParaphrasedQuestions(원본 질문들, N)  [신규] → List<EvalQuestion>
            │     └─ 내부적으로 GeminiGenerationClient.generate(prompt) 호출, 질문당 N개 paraphrase 파싱
            ├─ RetrievalRankFinder.findRank(q, maxK)                       (기존 재사용, paraphrase 질문에도 그대로 동작)
            └─ RetrievalAccuracyCalculator.calculate(results, kValues)     (기존 재사용, 순수 함수)
```

### 신규 컴포넌트

| 컴포넌트 | 위치 | 역할 |
| --- | --- | --- |
| `ParaphraseGenerator` | `application` | 원본 `EvalQuestion` 목록을 받아 Gemini로 질문마다 paraphrase N개를 생성하고, 각 paraphrase를 **원본과 같은 `expectedSource`/`expectedContent`**를 가진 새 `EvalQuestion`으로 변환 |
| `ParaphrasedRetrievalAccuracyEvalService` | `application` | 위 컴포넌트들을 순서대로 호출하는 오케스트레이터 (기존 `RetrievalAccuracyEvalService`와 동일한 패턴, paraphrase 생성 단계만 추가) |
| `ChatbotController` (기존 파일 수정) | `presentation/controller` | `POST /admin/eval/retrieval-accuracy-paraphrased` 엔드포인트 추가 |

### 핵심 로직 — `ParaphraseGenerator`

**프롬프트**: "아래 질문을 실제 사용자가 챗봇에게 물어볼 법한 자연스러운 다른 표현으로 N개 바꿔 써줘. 원래 의미는 유지하되 표현과 어순은 다르게. 한 줄에 하나씩, 번호나 설명 없이 질문 문장만 출력."

**파싱 (`parseLines`, 순수 함수, package-private)**: Gemini 응답을 줄바꿈으로 분리하고, 각 줄 앞의 번호/불릿/따옴표(`-`, `*`, `1.`, `"` 등)를 정규식으로 제거, 빈 줄은 건너뛰고, 요청한 개수(N)만큼만 취한다. Gemini가 N개보다 적게 반환하면 있는 만큼만 사용한다(예외를 던지지 않음 — `EvalQuestionExtractor`가 패턴 안 맞는 청크를 건너뛰는 것과 같은 방어적 패턴).

**`generateParaphrasedQuestions`**: 원본 `EvalQuestion` 목록의 각 항목에 대해 `paraphrase(question, N)`을 호출하고, 반환된 각 paraphrase 문자열을 `new EvalQuestion(paraphraseText, original.expectedSource(), original.expectedContent())`로 감싸 평탄화된 리스트로 반환한다. 정답 판정 기준(`expectedContent`)은 원본 질문의 것을 그대로 물려받는다 — "다르게 표현한 질문이어도 원래 그 FAQ 청크를 찾아야 정답"이라는 기준은 변하지 않기 때문이다.

### REST 엔드포인트

```
POST /api/chatbot/admin/eval/retrieval-accuracy-paraphrased?topKs=1,2,4,8&paraphrasesPerQuestion=2
```

- `topKs`: 콤마 구분 정수 목록, 기본값 `1,2,4,8` (기존과 동일)
- `paraphrasesPerQuestion`: 질문당 생성할 paraphrase 개수, 기본값 `2`
- 응답: 기존과 동일한 `RetrievalAccuracyReport` (단, `totalQuestions`는 29가 아니라 `29 × paraphrasesPerQuestion`)

## 테스트 계획 (TDD)

1. `ParaphraseGeneratorTest` —
   - `parseLines`: 정상적인 줄바꿈 응답에서 N개 추출
   - 번호(`1. `)·불릿(`- `)·따옴표가 섞인 응답에서도 깨끗하게 파싱
   - 빈 줄이 섞여 있어도 건너뜀
   - 요청한 개수보다 적게 반환되면 있는 만큼만 반환 (예외 없음)
   - `generateParaphrasedQuestions`: 원본 질문 여러 개 → 각각 N개씩 생성되어 총 `원본 수 × N`개 반환, 각 paraphrase의 `expectedSource`/`expectedContent`가 원본과 동일한지 (Mockito로 `GeminiGenerationClient` mock)
2. `ParaphrasedRetrievalAccuracyEvalServiceTest` — 하위 컴포넌트를 모두 mock으로 대체해 올바른 순서로 호출되고 결과가 조립되는지 (기존 `RetrievalAccuracyEvalServiceTest`와 동일한 패턴)

이번에도 DB·Gemini API 없이 전부 단위 테스트로 검증 가능하다. 실제 paraphrase 품질과 정확도 수치는 사용자가 로컬(Docker + GEMINI_API_KEY)에서 직접 실행해 확인한다.
