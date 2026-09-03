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
