package com.my.mindot_back.records.client;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.http.MediaType;

@Component
public class InsightAiClient {
    private final RestClient client;
    public InsightAiClient(@Qualifier("cbtRestClient") RestClient client) { this.client=client; }
    public static class Failure extends RuntimeException {
        public final String code;
        public Failure(String code) { super(code);this.code=code; }
    }
    @SuppressWarnings("unchecked")
    public Map<String,Object> call(String path,Map<String,Object> body) {
        try {
            Map<String,Object> result=client.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(Map.class);
            if(result==null)throw new Failure("EMPTY_RESPONSE");
            return result;
        } catch(RestClientResponseException e) {
            Map<?,?> payload=e.getResponseBodyAs(Map.class);
            Object detail=payload==null?null:payload.get("detail");
            String code=detail instanceof Map<?,?> m ? String.valueOf(m.get("code")) : "GENERATION_FAILED";
            throw new Failure(java.util.Set.of("RESYNC_REQUIRED","IN_PROGRESS","REQUEST_CONFLICT").contains(code)?code:"GENERATION_FAILED");
        } catch(org.springframework.web.client.RestClientException e) { throw new Failure("TRANSPORT_FAILED"); }
    }
    public void close(Long sid) {
        try {client.delete().uri("/internal/ai/reflections/{id}",sid).retrieve().toBodilessEntity();}
        catch(RuntimeException ignored) { /* Spring cancellation already committed. */ }
    }
}
