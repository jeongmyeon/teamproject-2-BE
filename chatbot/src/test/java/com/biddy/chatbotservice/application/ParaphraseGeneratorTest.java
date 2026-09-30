package com.biddy.chatbotservice.application;

import com.biddy.chatbotservice.domain.model.EvalQuestion;
import com.biddy.chatbotservice.infra.gemini.GeminiGenerationClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class ParaphraseGeneratorTest {

    @Mock
    private GeminiGenerationClient generationClient;

    private ParaphraseGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new ParaphraseGenerator(generationClient);
    }

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

    @Test
    void 원본_질문마다_요청한_개수만큼_paraphrase를_생성해서_같은_정답기준의_EvalQuestion으로_변환한다() {
        EvalQuestion original = new EvalQuestion("회원가입은 어떻게 하나요?", "01_회원.md", "원본 청크 내용");
        given(generationClient.generate(anyString()))
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
        given(generationClient.generate(anyString()))
                .willReturn("변형1\n변형2");

        List<EvalQuestion> result = generator.generateParaphrasedQuestions(List.of(q1, q2), 2);

        assertThat(result).hasSize(4);
        assertThat(result).extracting(EvalQuestion::expectedSource)
                .containsExactly("a.md", "a.md", "b.md", "b.md");
    }
}
