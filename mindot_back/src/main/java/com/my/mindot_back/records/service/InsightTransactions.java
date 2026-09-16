package com.my.mindot_back.records.service;

import com.my.mindot_back.ai.entity.*;
import com.my.mindot_back.ai.repository.AiJobsRepository;
import com.my.mindot_back.records.dto.InsightDtos.*;
import com.my.mindot_back.records.entity.*;
import com.my.mindot_back.records.repository.*;
import com.my.mindot_back.distortions.repository.DistortionTypesRepository;
import com.my.mindot_back.reports.service.ReportCacheInvalidationService;
import com.my.mindot_back.safety.service.SafetyEventsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;
import static com.my.mindot_back.records.service.InsightMapping.*;

/** All methods are short DB transactions. No AI/network client is injected here. */
@Service
@RequiredArgsConstructor
public class InsightTransactions {
    private final ReflectionSessionsRepository sessions;
    private final EmotionRecordsRepository records;
    private final AiJobsRepository jobs;
    private final SessionDistortionsRepository distortions;
    private final DistortionTypesRepository types;
    private final SafetyEventsService safety;
    // CBT 최종 확정으로 바뀐 점수·인지왜곡 통계의 리포트 캐시를 무효화
    private final ReportCacheInvalidationService reportCacheInvalidationService;


    private static ResponseStatusException conflict(String reason) { return new ResponseStatusException(HttpStatus.CONFLICT,reason); }
    private ReflectionSessions owned(Long user,Long sid) {
        var s=sessions.findLockedById(sid).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"성찰 세션을 찾을 수 없습니다."));
        if(!s.getUser().getId().equals(user))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"성찰 세션을 찾을 수 없습니다.");
        if(LegacyReflectionRetry.legacyOpen(s)) {
            var state=s.insight();long count=messages(s).size();
            // Seed the revision from existing history, without rewriting any row.
            if(number(state.get("revision"))<count) {state.put("revision",count);s.replaceInsight(state);}
        }
        return s;
    }
    private AiJobs lastJob(ReflectionSessions s) {
        long id=number(s.insight().get("lastJobId"));
        if(id!=0)return jobs.findById(id).orElse(null);
        if(!LegacyReflectionRetry.legacyOpen(s))return null;
        var legacy=jobs.findFirstByUser_IdAndEntityTypeAndEntityIdAndOperationOrderByIdDesc(
            s.getUser().getId(),AiJobEntityType.REFLECTION,s.getId(),AiJobOperation.QUESTION).orElse(null);
        return LegacyReflectionRetry.matchesSavedInput(s,legacy)?legacy:null;
    }
    private boolean processing(AiJobs j) { return j!=null && (j.getStatus()==AiJobStatus.PROCESSING || j.getStatus()==AiJobStatus.PENDING); }
    private void expire(ReflectionSessions s) {
        var j=lastJob(s);
        var deadline=LegacyReflectionRetry.deadline(j);
        if(processing(j) && deadline!=null && !deadline.isAfter(Instant.now())){j.fail("ATTEMPT_EXPIRED");j.cacheInsight(object("view",viewMap(view(s))));}
    }
    private AiJobs duplicate(ReflectionSessions s,String key,String command) {
        var j=jobs.findFirstByUser_IdAndEntityTypeAndEntityIdAndOperationAndIdempotencyKeyOrderByIdDesc(
            s.getUser().getId(),AiJobEntityType.REFLECTION,s.getId(),AiJobOperation.CBT_COMMAND,key).orElse(null);
        if(j!=null && !Objects.equals(j.getRequestPayload().get("command"),command))throw conflict("같은 요청 키에 다른 내용이 전달됐습니다.");
        return j;
    }
    private void match(ReflectionSessions s,Long revision) {
        if(revision==null || revision!=number(s.insight().get("revision")))throw conflict("화면이 변경됐습니다. 현재 성찰을 다시 확인해 주세요.");
    }
    private void openOnly(ReflectionSessions s) { if(s.getStatus()!=ReflectionSessionStatus.OPEN)throw conflict("종료된 성찰은 변경할 수 없습니다."); }
    private void available(ReflectionSessions s) {
        expire(s);if(processing(lastJob(s)))throw conflict("답변을 처리하고 있습니다.");
    }
    private AiJobs job(ReflectionSessions s,String key,String command,Map<String,Object> input,short attempt) {
        var j=AiJobs.create(s.getUser(),AiJobEntityType.REFLECTION,s.getId(),AiJobOperation.CBT_COMMAND,key);
        j.prepareInsight(object("command",command,"input",input),attempt,Instant.now().plusSeconds(210));
        j.startProcessing();return jobs.saveAndFlush(j);
    }
    private Map<String,Object> input(ReflectionSessions s,AiJobs j) {
        if(j==null)return object();
        if(j.getOperation()==AiJobOperation.QUESTION)return LegacyReflectionRetry.input(s,j);
        return map(map(j.getRequestPayload()).get("input"));
    }
    private Map<String,Object> pending(ReflectionSessions s,AiJobs j) {
        if(j==null)return null;
        var i=input(s,j);
        if(!i.containsKey("requestId"))return null;
        return object("requestId",i.get("requestId"),"attemptNo",j.getAttemptNo(),"inputRevision",i.get("inputRevision"),
            "userMessageNumber",map(i.get("userMessage")).get("messageNumber"));
    }
    private List<Map<String,Object>> historicalReviews(ReflectionSessions s) {
        List<Map<String,Object>> rows=new ArrayList<>();
        for(var phase:DistortionPhase.values())for(var d:distortions.findAllBySession_IdAndPhase(s.getId(),phase))
            rows.add(object("code",d.getDistortionType().getCode(),"phase",phase.name(),"reviewStatus",d.getReviewStatus().name()));
        return rows;
    }
    private Map<String,Object> restore(ReflectionSessions s) {
        var state=s.insight();var j=lastJob(s);
        Map<String,Object> proposal=map(state.get("currentProposal"));
        return object("mode","RESTORE","sessionId",s.getId(),"revision",number(state.get("revision")),"record",record(s),
            "messages",messages(s),"phase",proposal.isEmpty()?"DIALOGUE":"PROPOSAL_REVIEW","currentProposal",proposal.isEmpty()?null:proposal,
            "historicalTypeReviews",historicalReviews(s),"pendingJob",j!=null && j.getStatus()!=AiJobStatus.COMPLETED?pending(s,j):null);
    }
    private Prepared prepared(ReflectionSessions s,AiJobs j,boolean dispatch) {
        var input=input(s,j);
        var full=restore(s);boolean start="NEW".equals(input.get("kind"));
        Map<String,Object> request;
        if(start) {
            request=new LinkedHashMap<>(full);request.put("mode","NEW");request.put("pendingJob",pending(s,j));
        } else request=object("sessionId",s.getId(),"requestId",input.get("requestId"),"attemptNo",j==null?null:j.getAttemptNo(),
            "baseRevision",input.get("baseRevision"),"inputRevision",input.get("inputRevision"),"userMessage",input.get("userMessage"));
        return new Prepared(s.getId(),j==null?null:j.getId(),j==null?1:j.getAttemptNo(),number(input.get("inputRevision")),request,full,start,dispatch,view(s));
    }

    private Prepared replay(ReflectionSessions s,AiJobs job) {
        var p=prepared(s,job,false);
        return new Prepared(p.sessionId(),p.jobId(),p.attemptNo(),p.inputRevision(),p.request(),p.restore(),p.start(),false,cachedView(s,job));
    }

    @Transactional
    public Prepared open(Long user,Open body,String key,Long revision) {
        ReflectionSessions s;
        if(body.emotionRecordId()!=null) {
            var e=records.findLockedById(body.emotionRecordId()).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"기록을 찾을 수 없습니다."));
            if(!e.getUser().getId().equals(user))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"기록을 찾을 수 없습니다.");
            if(e.getAutomaticThought()==null || e.getAutomaticThought().isBlank())throw conflict("처음 생각을 기록한 뒤 성찰을 시작해 주세요.");
            s=sessions.findByEmotionRecord_IdAndUser_Id(e.getId(),user).orElse(null);
            if(s==null)s=sessions.saveAndFlush(ReflectionSessions.create(e.getUser(),e));
            else s=owned(user,s.getId());
        } else s=owned(user,body.sessionId());
        String command="OPEN:"+body;
        expire(s);var duplicate=duplicate(s,key,command);
        if(duplicate!=null)return replay(s,duplicate);
        if(body.sessionId()!=null)match(s,revision);
        if(s.getStatus()!=ReflectionSessionStatus.OPEN)return prepared(s,lastJob(s),false);
        if(processing(lastJob(s)))return prepared(s,lastJob(s),false);
        available(s);
        var state=s.insight();state.putIfAbsent("revision",0L);state.putIfAbsent("record",record(s));
        s.replaceInsight(state);
        if(messages(s).isEmpty() && lastJob(s)==null) {
            long inputRevision=number(state.get("revision"));
            var input=object("kind","NEW","requestId",UUID.randomUUID().toString(),"inputRevision",inputRevision);
            var j=job(s,key,command,input,(short)1);state.put("lastJobId",j.getId());s.replaceInsight(state);
            return prepared(s,j,true);
        }
        // Opening an existing session only hydrates/displays it; never regenerates.
        var receipt=job(s,key,command,object(),(short)1);receipt.complete(null,"cbt-insight-1");receipt.cacheInsight(object("view",viewMap(view(s))));
        return prepared(s,receipt,false);
    }

    @Transactional
    public Prepared turn(Long user,Long sid,String key,Long revision,Turn body) {
        var s=owned(user,sid);expire(s);String command="TURN:"+body.answer();var duplicate=duplicate(s,key,command);
        if(duplicate!=null)return replay(s,duplicate);
        match(s,revision);openOnly(s);available(s);
        var previous=lastJob(s);
        if(previous!=null && previous.getStatus()==AiJobStatus.FAILED)throw conflict("저장된 답변의 재시도를 먼저 선택해 주세요.");
        if(messages(s).isEmpty())throw conflict("첫 질문을 먼저 생성해 주세요.");
        var state=s.insight();long base=number(state.get("revision"));
        var message=object("messageNumber",messages(s).size()+1,"role","USER","content",body.answer(),"createdAt",Instant.now().toString());
        s.appendInsightMessage(message);state.put("revision",base+1);
        var input=object("kind","TURN","requestId",UUID.randomUUID().toString(),"baseRevision",base,"inputRevision",base+1,"userMessage",message);
        var j=job(s,key,command,input,(short)1);state.put("lastJobId",j.getId());s.replaceInsight(state);
        return prepared(s,j,true);
    }

    @Transactional
    public Prepared retry(Long user,Long sid,String key,Long revision) {
        var s=owned(user,sid);expire(s);var duplicate=duplicate(s,key,"RETRY");
        if(duplicate!=null)return replay(s,duplicate);
        match(s,revision);openOnly(s);available(s);var previous=lastJob(s);
        if(previous==null || previous.getStatus()!=AiJobStatus.FAILED)throw conflict("재시도할 생성 작업이 없습니다.");
        if(previous.getAttemptNo()==Short.MAX_VALUE)throw conflict("재시도 횟수 한도입니다.");
        var input=input(s,previous);
        var j=job(s,key,"RETRY",input,(short)(previous.getAttemptNo()+1));
        var state=s.insight();state.put("lastJobId",j.getId());s.replaceInsight(state);
        return prepared(s,j,true);
    }

    @Transactional
    public SessionView complete(Long user,Prepared p,Map<String,Object> result) {
        var s=owned(user,p.sessionId());expire(s);var j=jobs.findById(p.jobId()).orElseThrow();
        if(s.getStatus()!=ReflectionSessionStatus.OPEN || !processing(j) || !Objects.equals(lastJob(s).getId(),j.getId())
            || j.getAttemptNo()!=p.attemptNo() || number(s.insight().get("revision"))!=p.inputRevision())return view(s);
        var input=map(j.getRequestPayload().get("input"));
        if(number(result.get("sessionId"))!=s.getId() || !Objects.equals(result.get("requestId"),input.get("requestId"))
            || number(result.get("attemptNo"))!=p.attemptNo() || number(result.get("inputRevision"))!=p.inputRevision()
            || number(result.get("revision"))!=p.inputRevision()+1)throw conflict("AI 응답의 요청 연결이 일치하지 않습니다.");
        String outcome=(String)result.get("outcome");
        if(!Set.of("QUESTION","HELP","EXPLAIN_PROPOSAL","PROPOSAL","CONTROL","SAFETY_CLARIFY","SAFETY_STOP","UNRESOLVED").contains(outcome))throw conflict("AI 응답 동작이 올바르지 않습니다.");
        var message=map(result.get("assistantMessage"));
        if(number(message.get("messageNumber"))!=messages(s).size()+1 || !"ASSISTANT".equals(message.get("role"))
            || !(message.get("content") instanceof String text) || text.isBlank())throw conflict("AI 표시 응답이 올바르지 않습니다.");
        var state=s.insight();var previous=map(state.get("currentProposal"));var proposal=map(result.get("currentProposal"));
        if("EXPLAIN_PROPOSAL".equals(outcome) && (previous.isEmpty() || !previous.equals(proposal)))throw conflict("설명 중 제안이 변경됐습니다.");
        if("PROPOSAL".equals(outcome) && (proposal.isEmpty() || !(proposal.get("afterText") instanceof String a) || a.isBlank()
            || !"cbt-insight-1".equals(proposal.get("resultFormatVersion"))))throw conflict("확인할 제안이 없습니다.");
        if(!Set.of("EXPLAIN_PROPOSAL","PROPOSAL").contains(outcome) && !proposal.isEmpty())throw conflict("일반 응답에 이전 제안이 남았습니다.");
        if(!previous.isEmpty() && !previous.equals(proposal))archive(state,previous);
        state.put("currentProposal",proposal.isEmpty()?null:proposal);state.put("phase",proposal.isEmpty()?"DIALOGUE":"PROPOSAL_REVIEW");
        state.put("revision",p.inputRevision()+1);state.put("issue",result.get("issue"));state.put("resultFormatVersion","cbt-insight-1");
        s.appendInsightMessage(message);s.replaceInsight(state);
        if("SAFETY_STOP".equals(outcome)) { s.stopForSafety();safety.recordIfRiskDetected(s.getEmotionRecord(),j,"CRISIS","IMMEDIATE_DANGER"); }
        j.complete("gpt-4o-mini","cbt-insight-1");var view=view(s);j.cacheInsight(object("aiResponse",result,"view",viewMap(view)));
        return view;
    }
    @SuppressWarnings("unchecked")
    private void archive(Map<String,Object> state,Map<String,Object> proposal) {
        var history=new ArrayList<Map<String,Object>>((List<Map<String,Object>>)state.getOrDefault("priorProposals",List.of()));
        history.add(new LinkedHashMap<>(proposal));state.put("priorProposals",history);
    }
    @Transactional
    public SessionView fail(Long user,Prepared p,String error) {
        var s=owned(user,p.sessionId());var j=jobs.findById(p.jobId()).orElseThrow();
        if(processing(j) && j.getAttemptNo()==p.attemptNo()) {j.fail(error);j.cacheInsight(object("view",viewMap(view(s))));}
        return view(s);
    }
    @Transactional
    public SessionView get(Long user,Long sid) {var s=owned(user,sid);expire(s);return view(s);}

    @Transactional
    public SessionView confirm(Long user,Long sid,String key,Long revision,Confirm body) {
        var s=owned(user,sid);expire(s);var duplicate=duplicate(s,key,"CONFIRM:"+body);
        if(duplicate!=null)return cachedView(s,duplicate);
        match(s,revision);openOnly(s);available(s);var last=lastJob(s);
        if(last!=null && last.getStatus()!=AiJobStatus.COMPLETED)throw conflict("새 답변을 처리한 뒤 결과를 확인해 주세요.");
        var state=s.insight();var proposal=map(state.get("currentProposal"));
        if(proposal.isEmpty() || !Objects.equals(body.proposalId(),proposal.get("proposalId")))throw conflict("현재 확인 대상과 다른 제안입니다.");
        @SuppressWarnings("unchecked") var suggested=(List<Map<String,Object>>)proposal.get("suggestions");
        var codes=new HashSet<String>();for(var item:suggested)codes.add((String)item.get("code"));
        var reviewed=new HashSet<String>();for(var review:body.reviews())if(!reviewed.add(review.code()))throw conflict("유형 검토가 중복됐습니다.");
        if(!codes.equals(reviewed))throw conflict("제안한 모든 유형을 수락 또는 거부해 주세요.");
        var existing=distortions.findAllBySession_IdAndPhase(sid,DistortionPhase.BEFORE);
        for(var review:body.reviews()) {
            var d=existing.stream().filter(x->x.getDistortionType().getCode().equals(review.code())).findFirst().orElse(null);
            if(d==null)d=SessionDistortions.createInsightProposal(s,types.findByCode(review.code()).orElseThrow(()->conflict("등록되지 않은 인지왜곡 유형입니다.")));
            d.applyUserReview(DistortionReviewStatus.valueOf(review.reviewStatus()));distortions.save(d);
        }
        var confirmed=new LinkedHashMap<>(proposal);
        confirmed.put("reviews",body.reviews().stream().map(r->object("code",r.code(),"reviewStatus",r.reviewStatus())).toList());
        confirmed.put("beforeBeliefStrength",body.beforeBeliefStrength());confirmed.put("afterBeliefStrength",body.afterBeliefStrength());
        confirmed.put("finalEmotionIntensity",body.finalEmotionIntensity());confirmed.put("helpfulnessScore",body.helpfulnessScore());
        confirmed.put("confirmedAt",Instant.now().toString());confirmed.put("userConfirmed",true);
        s.confirmInsight(confirmed,body.beforeBeliefStrength(),body.afterBeliefStrength(),body.finalEmotionIntensity(),body.helpfulnessScore());
        // 확정 CBT의 완료 수·도움 점수·인지왜곡 통계가 반영되도록
        // 연결된 감정 기록 날짜의 리포트 캐시만 무효화
        reportCacheInvalidationService.invalidateByOccurredAt(
                user,
                s.getEmotionRecord().getOccurredAt()
        );
        state.put("confirmedResult",confirmed);state.put("currentProposal",null);state.put("phase",null);state.put("revision",number(state.get("revision"))+1);s.replaceInsight(state);
        var receipt=job(s,key,"CONFIRM:"+body,object(),(short)1);receipt.complete(null,"cbt-insight-1");receipt.cacheInsight(object("view",viewMap(view(s))));
        return view(s);
    }
    @Transactional
    public SessionView cancel(Long user,Long sid,String key,Long revision) {
        var s=owned(user,sid);expire(s);var duplicate=duplicate(s,key,"CANCEL");if(duplicate!=null)return cachedView(s,duplicate);
        match(s,revision);openOnly(s);var j=lastJob(s);if(processing(j))j.fail("CANCELLED");
        var state=s.insight();var proposal=map(state.get("currentProposal"));if(!proposal.isEmpty())archive(state,proposal);
        state.put("currentProposal",null);state.put("phase",null);state.put("revision",number(state.get("revision"))+1);s.replaceInsight(state);s.cancel();
        var receipt=job(s,key,"CANCEL",object(),(short)1);receipt.complete(null,"cbt-insight-1");receipt.cacheInsight(object("view",viewMap(view(s))));
        return view(s);
    }
    private SessionView view(ReflectionSessions s) {
        var state=s.insight();var j=lastJob(s);boolean waiting=j!=null && j.getStatus()!=AiJobStatus.COMPLETED;
        var proposal=map(state.get("currentProposal"));boolean open=s.getStatus()==ReflectionSessionStatus.OPEN;
        Map<String,Object> job=j==null?null:object("jobId",j.getId(),"attemptNo",j.getAttemptNo(),"status",j.getStatus().name(),"retryable",open && j.getStatus()==AiJobStatus.FAILED,
            "errorCode",j.getErrorCode(),"deadline",LegacyReflectionRetry.deadline(j)==null?null:LegacyReflectionRetry.deadline(j).toString());
        Map<String,Object> legacy=s.confirmedInsight()==null && s.getStatus()==ReflectionSessionStatus.COMPLETED
            ?object("alternativeThoughtText",s.getAlternativeThoughtText(),"evidenceForText",s.getEvidenceForText(),"evidenceAgainstText",s.getEvidenceAgainstText(),
                "beforeBeliefStrength",s.getBeforeBeliefStrength(),"afterBeliefStrength",s.getAfterBeliefStrength(),
                "finalEmotionIntensity",s.getFinalEmotionIntensity(),"helpfulnessScore",s.getHelpfulnessScore()):null;
        return new SessionView(s.getId(),number(state.get("revision")),s.getStatus().name(),open && !proposal.isEmpty()?"PROPOSAL_REVIEW":open?"DIALOGUE":null,
            record(s),messages(s),open && !waiting && !proposal.isEmpty()?proposal:null,s.confirmedInsight(),job,(String)state.get("issue"),
            (String)state.getOrDefault("resultFormatVersion","legacy"),legacy);
    }
    @SuppressWarnings("unchecked")
    private SessionView cachedView(ReflectionSessions s,AiJobs job) {
        if(job==null || job.getResponsePayload()==null)return view(s);
        var v=map(job.getResponsePayload().get("view"));
        if(v.isEmpty())return view(s);
        return new SessionView(number(v.get("sessionId")),number(v.get("revision")),(String)v.get("status"),(String)v.get("phase"),
            map(v.get("record")),(List<Map<String,Object>>)v.get("messages"),nullableMap(v.get("currentProposal")),
            nullableMap(v.get("confirmedResult")),nullableMap(v.get("job")),(String)v.get("issue"),
            (String)v.get("resultFormatVersion"),nullableMap(v.get("legacyResult")));
    }
    private Map<String,Object> nullableMap(Object value) {return value==null?null:map(value);}
    private Map<String,Object> viewMap(SessionView v) {
        return object("sessionId",v.sessionId(),"revision",v.revision(),"status",v.status(),"phase",v.phase(),"record",v.record(),"messages",v.messages(),
            "currentProposal",v.currentProposal(),"confirmedResult",v.confirmedResult(),"job",v.job(),"issue",v.issue(),"resultFormatVersion",v.resultFormatVersion(),"legacyResult",v.legacyResult());
    }
}
