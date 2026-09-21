package com.my.mindot_back.records.service;
import com.my.mindot_back.records.client.InsightAiClient;
import com.my.mindot_back.records.dto.InsightDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import com.my.mindot_back.users.service.ConsentEventsService;

/** Network work runs after the prepare transaction and before the commit transaction. */
@Service
@RequiredArgsConstructor
public class InsightService {
    private final InsightTransactions tx;
    private final InsightAiClient client;
    private final InsightEmbeddingService embeddings;
    private final ConsentEventsService consentEventsService;

    public SessionView open(Long user,Open body,String key,Long revision) {
        // CBT 생성을 시작하기 전에 최신 AI 분석 동의 상태 확인
        consentEventsService.requireAiAnalysisConsent(user);

        var p=tx.open(user,body,key,revision);
        if(p.dispatch())return run(user,p);
        if("OPEN".equals(p.view().status()) && (p.view().job()==null || !java.util.Set.of("PROCESSING","PENDING").contains(p.view().job().get("status")))) {
            // Failed hydration never starts a second generation. The next delta can request resync.
            try {client.call("/internal/ai/reflections/start",p.restore());}catch(InsightAiClient.Failure ignored) { }
        }
        return p.view();
    }
    public SessionView turn(Long user,Long sid,String key,Long revision,Turn body) {
        // 다음 AI 질문을 요청하기 전에 최신 AI 분석 동의 상태 확인
        consentEventsService.requireAiAnalysisConsent(user);

        return run(user,tx.turn(user,sid,key,revision,body));}
    public SessionView retry(Long user,Long sid,String key,Long revision) {
        // 실패한 AI 생성을 재시도하기 전에 최신 AI 분석 동의 상태 확인
        consentEventsService.requireAiAnalysisConsent(user);

        return run(user,tx.retry(user,sid,key,revision));}
    private SessionView run(Long user,Prepared p) {
        if(!p.dispatch())return p.view();
        try {
            java.util.Map<String,Object> result;
            String path=p.start()?"/internal/ai/reflections/start":"/internal/ai/reflections/turn";
            try {result=client.call(path,p.request());}
            catch(InsightAiClient.Failure e) {
                if(!"RESYNC_REQUIRED".equals(e.code))throw e;
                client.call("/internal/ai/reflections/start",p.restore());
                result=client.call(path,p.request()); // Exactly one typed resync, same saved delta.
            }
            return tx.complete(user,p,result);
        } catch(RuntimeException e) {
            return tx.fail(user,p,e instanceof InsightAiClient.Failure f?f.code:"RESULT_COMMIT_FAILED");
        }
    }
    public SessionView get(Long user,Long sid) {return tx.get(user,sid);}
    public SessionView confirm(Long user,Long sid,String key,Long revision,Confirm body) {
        // CBT 결과 확정과 임베딩 생성을 시작하기 전에 동의 상태 확인
        consentEventsService.requireAiAnalysisConsent(user);

        var result=tx.confirm(user,sid,key,revision,body);
        try {embeddings.submit(user,sid);}catch(RuntimeException ignored) { /* Explicit embedding retry remains available. */ } // Independent from committed confirmation; failure is retryable.
        return result;
    }
    public SessionView cancel(Long user,Long sid,String key,Long revision) {
        var result=tx.cancel(user,sid,key,revision);
        try {embeddings.closeRuntime(sid);}catch(RuntimeException ignored) { /* DB cancellation already rejects late responses; runtime has TTL. */ }
        return result;
    }
}
