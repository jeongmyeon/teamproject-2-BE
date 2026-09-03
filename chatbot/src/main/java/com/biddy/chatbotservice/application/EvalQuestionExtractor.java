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
