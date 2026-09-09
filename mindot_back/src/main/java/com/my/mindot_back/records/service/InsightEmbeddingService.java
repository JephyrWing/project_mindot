package com.my.mindot_back.records.service;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InsightEmbeddingService {
    private final ReflectionSessionsService legacyEmbeddingService;
    private final com.my.mindot_back.records.client.InsightAiClient client;
    @Async
    public void closeRuntime(Long session) {client.close(session);}
    @Async
    public void submit(Long user,Long session) {
        try {legacyEmbeddingService.retryEmbedding(user,session);}
        catch(RuntimeException ignored) { /* Existing EMBED job keeps failure; confirmation stays completed. */ }
    }
}
