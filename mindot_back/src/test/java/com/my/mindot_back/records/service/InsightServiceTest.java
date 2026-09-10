package com.my.mindot_back.records.service;

import com.my.mindot_back.records.client.InsightAiClient;
import com.my.mindot_back.records.dto.InsightDtos.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** No Spring context/database; source prepared for post-review execution. */
class InsightServiceTest {
    private SessionView view() {
        return new SessionView(1L,2L,"OPEN","DIALOGUE",Map.of(),List.of(),null,null,null,null,"cbt-insight-1",null);
    }
    @Test void duplicateReceiptDoesNotDispatchGeneration() {
        var tx=mock(InsightTransactions.class);var client=mock(InsightAiClient.class);
        var service=new InsightService(tx,client,mock(InsightEmbeddingService.class));
        var p=new Prepared(1L,2L,(short)1,2L,Map.of(),Map.of(),false,false,view());
        when(tx.retry(1L,1L,"key",2L)).thenReturn(p);
        assertSame(p.view(),service.retry(1L,1L,"key",2L));
        verifyNoInteractions(client);
    }
    @Test void resyncRestoresThenReplaysExactlyTheSavedDelta() {
        var tx=mock(InsightTransactions.class);var client=mock(InsightAiClient.class);
        var service=new InsightService(tx,client,mock(InsightEmbeddingService.class));
        var request=Map.<String,Object>of("requestId","same","inputRevision",2L);
        var restore=Map.<String,Object>of("mode","RESTORE","revision",2L);
        var p=new Prepared(1L,2L,(short)1,2L,request,restore,false,true,view());
        when(tx.retry(1L,1L,"key",2L)).thenReturn(p);
        var result=Map.<String,Object>of("outcome","QUESTION");
        when(client.call("/internal/ai/reflections/turn",request))
            .thenThrow(new InsightAiClient.Failure("RESYNC_REQUIRED")).thenReturn(result);
        when(tx.complete(1L,p,result)).thenReturn(view());
        service.retry(1L,1L,"key",2L);
        var order=inOrder(client,tx);
        order.verify(tx).retry(1L,1L,"key",2L);
        order.verify(client).call("/internal/ai/reflections/turn",request);
        order.verify(client).call("/internal/ai/reflections/start",restore);
        order.verify(client).call("/internal/ai/reflections/turn",request);
        order.verify(tx).complete(1L,p,result);
        verifyNoMoreInteractions(client);
    }
    @Test void failedEmbeddingDispatchCannotUndoCommittedConfirmation() {
        var tx=mock(InsightTransactions.class);var embeddings=mock(InsightEmbeddingService.class);
        var service=new InsightService(tx,mock(InsightAiClient.class),embeddings);
        var body=new Confirm("proposal",List.of(),(short)80,(short)40,(short)4,(short)3);
        var saved=view();when(tx.confirm(1L,1L,"key",2L,body)).thenReturn(saved);
        doThrow(new IllegalStateException("executor unavailable")).when(embeddings).submit(1L,1L);
        assertSame(saved,service.confirm(1L,1L,"key",2L,body));
    }
}
