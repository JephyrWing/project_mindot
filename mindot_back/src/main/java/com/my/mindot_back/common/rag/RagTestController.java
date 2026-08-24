// 임베딩 요청이 정상 동작하는 지 확인하는 임시 테스트
package com.my.mindot_back.common.rag;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.Map;

@RestController
@RequestMapping
@RequiredArgsConstructor
public class RagTestController {

    // 텍스트를 임베딩 벡터로 변환하는 공통 객체
    private final RagUtils ragUtils;

    // 요청
    @GetMapping("/embedding")
    public Map<String, Object> createEmbedding(
            // postman url의 text 값으로 임베딩할 문장을 받음
            @RequestParam String text
    ) {
        // 빈문자열이면 요청x
        if (text.isBlank()){
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "임베딩할 텍스트를 입력해주세요."
            );
        }

        // Spring AI EmbeddingModel을 통해 OpenAI에 임베딩 요청
        float[] embedding = ragUtils.embed(text);

        /*
         * 1,536개 전체를 응답으로 보내면 너무 길므로
         * 테스트에서는 차원 수와 앞 5개 숫자만 반환
         */
        return Map.of(
                "dimensions", embedding.length,
                "preview", Arrays.copyOf(embedding, 5)
        );
    }
}
