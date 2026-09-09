package com.my.mindot_back.records.controller;
import com.my.mindot_back.records.dto.InsightDtos.*;
import com.my.mindot_back.records.dto.OpenReflectionSessionResponseDto;
import com.my.mindot_back.records.service.InsightService;
import com.my.mindot_back.records.service.ReflectionSessionsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

@RestController
@RequestMapping("/api/reflections")
@RequiredArgsConstructor
public class ReflectionSessionsController {
    private final InsightService service;
    private final ReflectionSessionsService existing;
    private String key(String value) {
        if(value==null || value.isBlank() || value.length()>120)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"요청 키가 올바르지 않습니다.");
        return value;
    }
    private Long revision(String value) {
        if(value==null)return null;
        try {long revision=Long.parseLong(value.replace("\"",""));if(revision<0)throw new NumberFormatException();return revision;}
        catch(NumberFormatException e) {throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"성찰 변경 번호가 올바르지 않습니다.");}
    }
    private ResponseEntity<SessionView> response(SessionView view) {
        boolean processing=view.job()!=null && List.of("PROCESSING","PENDING").contains(view.job().get("status"));
        return ResponseEntity.status(processing?HttpStatus.ACCEPTED:HttpStatus.OK).eTag(Long.toString(view.revision())).body(view);
    }
    @PostMapping("/open")
    public ResponseEntity<SessionView> open(@AuthenticationPrincipal Long user,@Valid @RequestBody Open body,
        @RequestHeader("Idempotency-Key") String key,@RequestHeader(value="If-Match",required=false) String revision) {
        return response(service.open(user,body,key(key),revision(revision)));
    }
    @GetMapping("/{sid}")
    public ResponseEntity<SessionView> get(@AuthenticationPrincipal Long user,@PathVariable Long sid) {return response(service.get(user,sid));}
    @PostMapping("/{sid}/turn")
    public ResponseEntity<SessionView> turn(@AuthenticationPrincipal Long user,@PathVariable Long sid,@Valid @RequestBody Turn body,
        @RequestHeader("Idempotency-Key") String key,@RequestHeader("If-Match") String revision) {
        return response(service.turn(user,sid,key(key),revision(revision),body));
    }
    @PostMapping("/{sid}/retry")
    public ResponseEntity<SessionView> retry(@AuthenticationPrincipal Long user,@PathVariable Long sid,
        @RequestHeader("Idempotency-Key") String key,@RequestHeader("If-Match") String revision) {
        return response(service.retry(user,sid,key(key),revision(revision)));
    }
    @PostMapping("/{sid}/confirm")
    public ResponseEntity<SessionView> confirm(@AuthenticationPrincipal Long user,@PathVariable Long sid,@Valid @RequestBody Confirm body,
        @RequestHeader("Idempotency-Key") String key,@RequestHeader("If-Match") String revision) {
        return response(service.confirm(user,sid,key(key),revision(revision),body));
    }
    @PostMapping("/{sid}/cancel")
    public ResponseEntity<SessionView> cancel(@AuthenticationPrincipal Long user,@PathVariable Long sid,
        @RequestHeader("Idempotency-Key") String key,@RequestHeader("If-Match") String revision) {
        return response(service.cancel(user,sid,key(key),revision(revision)));
    }
    @GetMapping("/open")
    public List<OpenReflectionSessionResponseDto> openSessions(@AuthenticationPrincipal Long user) {return existing.getOpenSessions(user);}
    @PostMapping("/{sid}/retry-embedding")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void retryEmbedding(@AuthenticationPrincipal Long user,@PathVariable Long sid) {existing.retryEmbedding(user,sid);}
}
