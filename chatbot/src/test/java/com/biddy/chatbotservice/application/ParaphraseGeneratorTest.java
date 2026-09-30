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
