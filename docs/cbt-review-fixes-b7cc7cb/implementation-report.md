# b7cc7cb 전체 리뷰 F1–F3 수정 보고서

이 제출은 수정 후 재리뷰용이다. 입력 리뷰의 `CHANGES_REQUIRED`를 자체 통과로 변경하지 않았다. 모든 테스트 상태는 `NOT_RUN_PENDING_GPT_FULL_BRANCH_REVIEW`이며 다음 단계는 ChatGPT가 최종 원격 커밋을 읽는 것이다.

- 시작 로컬/원격: `b7cc7cba4bf33ecc7361e32c06bde834206cd3ad`, `fix/CBTAI`. 작업 트리는 깨끗했고 fetch 후 원격도 동일했다.
- 입력 ZIP SHA256: `351bab6fd240a8240a3ae517e53c5497dd64ee6b4d71ecc193a52527c2f5c43a`.
- 입력 리뷰 원장 SHA256: `2c3a6ac2f0b2e43019a05bf7a347c039b389196a33ad9e35fd3eb21f1c1fd0dd`. 동봉 review.json의 원장 해시와 실제 파일을 대조했다.
- 최종 로컬/원격 SHA, 전체 tracked 및 자사 파일 hash, ZIP bytes/hash는 저장소 밖 delivery.json/full-tracked-manifest.json에 기록한다. 기존 소스와 과거 raw를 덮어쓰지 않는다.

## F1 — 기록 확정과 OPEN의 실패 복구 분리

수정 파일: `mindot_front/src/components/CBT/CBT.jsx`, `mindot_front/src/utils/reflections/confirmThoughtForOpen.js`.

기록 확정 성공 또는 재조회로 확인한 성공 직후 `confirmedRecord` 상태를 보관하고 입력 폼을 닫는다. 이후 OPEN 실패가 발생해도 기록을 다시 확정하지 않으며 같은 OPEN key/body를 재전달한다. OPEN이 성공해야만 기록 확정 완료 상태로 전환하던 순서를 바로잡았다.

확정 전 GET으로 이미 COMPLETE인지 확인한다. 확정 응답 유실·409·서버 오류 후에도 GET으로 확인하며, COMPLETE이고 실제 저장된 automaticThought가 이번 제출값과 정확히 같을 때만 확정 단계를 완료한다. 다른 생각이면 충돌을 안내하고 OPEN으로 진행하지 않는다. 아직 PARTIAL이면 원래 오류를 유지한다. 서버의 PARTIAL 전용 확정 규칙은 수정하지 않았고 COMPLETE 덮어쓰기나 포괄적 409 무시는 없다.

DailyCare/기록 상세 → App.handleCbtOpen → CBT와 직접 URL/재진입을 확인했다. 새로 진입하면 저장된 기록부터 조회하고, OPEN 응답 유실 후 같은 화면에서는 key를 유지한다. 페이지 재진입 시 새로운 key라도 Spring의 기록별 세션 소유권/중복 조회를 사용한다. App의 세션 URL 교체와 키, 기록 조회/확정 API, 목록의 기존 정렬·페이지 조건은 변경하지 않았다.

작성한 `confirmThoughtForOpen.test.js`는 응답 유실 후 일치 확인, 이미 COMPLETE인 기록의 재확정 방지, 다른 저장 생각 충돌, 아직 PARTIAL인 409를 다룬다. 테스트와 실제 브라우저 상태 전이 검증은 실행하지 않았다. 재리뷰 후 F1 브라우저 검증에서는 **확정 성공→OPEN 실패→다시 시작** 및 **확정 응답 유실→GET 실패→재시도**를 별도로 확인해야 한다.

## F2 — 구형 QUESTION 작업과 저장 답변 연결

수정 파일: `AiJobsRepository.java`, `InsightTransactions.java`, 신규 `LegacyReflectionRetry.java`, 구형 `ReflectionSessionStartTransactionService.java`/`ReflectionSessionTurnTransactionService.java`의 결과 수신 경계. 모두 `mindot_back/src/main/java/com/my/mindot_back/` 아래다.

새 lastJobId가 없는 구형 OPEN에 한해 같은 사용자·세션의 최신 QUESTION job을 조회한다. 구형 행의 현재 questionCode, 실제 저장된 마지막 answer, answeredAt 이후 생성된 job이라는 기계적 연결을 함께 확인한다. 최신 job이 COMPLETED이거나 현재 단계가 CONFIRM_REQUIRED이면 실패로 취급하지 않는다. 미답변 질문, 다른 과거 실패, 종료 상태도 재시도 대상으로 만들지 않는다. 날짜 정보가 불충분하면 실패라고 추정하지 않는다.

새 메타데이터가 없는 세션은 원래 메시지 개수로 revision의 시작 경계를 잡되 question_answers 행을 수정하지 않는다. 구형 실패는 retryable, 진행 중은 PROCESSING/PENDING으로 보여 주며 명시적 RETRY 전에 추가 USER 입력을 받지 않는다. 구형 job에 deadline이 없으면 기존 210초를 createdAt부터 계산해 영구 대기를 피한다. 새 예산이나 큐는 없다.

명시적 RETRY만 기존 job ID에서 안정적인 requestId를 만들고 새 CBT_COMMAND 시도를 저장한다. 구형 답변 2번이 이미 이력에 있으면 full RESTORE의 revision=2/pending USER=2와 TURN의 baseRevision=1/inputRevision=2를 연결한다. 그 USER 행을 다시 append하지 않는다. 처음 질문 실패로 이력이 비어 있으면 NEW/revision=0/빈 messages를 사용한다. 실패한 구형 세션을 open하는 작업 자체는 RESTORE만 하며 생성하지 않는다. 진행 중이면 현재 DB view를 반환하고 기존 실행을 덮어쓰지 않는다.

구형 결과가 아직 도착할 수 있는 경계에는 세션 잠금과 OPEN/작업 상태/deadline/새 lastJobId 검사를 추가했다. 취소·만료·새 RETRY 이후의 오래된 결과는 문답이나 제안을 덮어쓰지 않는다. 구형 정상 성공은 기존 저장 의미를 유지한다. 새 assistant 행을 추가하지 않는 구형 성공 제안도 revision을 올려 이전 PROCESSING 화면이 남지 않게 했다. 구형 모델이나 endpoint는 다시 활성화하지 않았고 일괄 변환·migration·삭제도 없다.

`LegacyReflectionRetryTest.java`는 실제 엔티티와 메모리 repository fixture로 실패→RESTORE→RETRY의 이력 경계, 진행/만료, 성공 제안/미답변, 과거 무관한 실패/종료, 최초 NEW 실패, 행 추가 없는 성공 revision을 다룬다. 컴파일만 했다. 기존 신형 InsightServiceTest/InsightMappingTest와 AI의 RESTORE/pending-delta 경로도 소스로 대조했다.

## F3 — 확정 AFTER 사례와 반복 수락 유형의 자격 분리

수정 파일: `records/dto/PatternSimilarCaseDto.java`, `records/service/EmotionRecordsService.java`.

새 형식은 userConfirmed=true이며 비어 있지 않은 afterText가 있는 사례를 전달한다. 수락 코드가 없어도 사례를 유지한다. 구형은 기존 수락 코드 조건과 대안 문구의 의미를 유지한다. 검색 실패 안내도 “인지왜곡이 있는 사례”라는 전제에서 “검색에 활용할 수 있는 확정 사례”로 수정했다.

실제 조회 소유권, COMPLETED+userConfirmed, 벡터 유사도/개수 조건, 전체 확정 세션 수·날짜·도움 점수 자격은 변경하지 않았다. AI `pattern_explanation.py`는 원래 confirmedResult.reviews 중 CONFIRMED만 반복 집계하며, 도움이 된 AFTER 선택은 별도로 score≥3을 사용한다. 코드가 없으면 반복 유형 없음으로 표시한다. 거부 코드를 복권하거나 새 라벨을 만드는 처리는 없다.

DailyCare는 patternSummary와 helpfulAlternativeThought를 별도로 표시한다. EmotionRecordDetail도 빈 반복 코드 목록의 “확인된 패턴 없음”과 도움이 된 생각을 함께 표시한다. 응답 DTO와 프런트 필드는 그대로이므로 두 화면을 재작성하지 않았다. 확정 결과의 상세/주간/PDF, context·context+thought 임베딩 및 임베딩 재시도는 수락 코드 수를 승인 조건으로 사용하지 않는 것을 재확인했다. 구형 차집합 집계와 새 CONFIRMED_INSIGHT 집계도 유지된다.

작성한 `PatternCaseEligibilityTest.java`와 `mindot_ai/tests/test_pattern_confirmed_after.py`는 전체 거부·혼합 수락/거부·미확정/빈 AFTER·구형 및 도움 점수 경계를 다룬다. Java 테스트 소스는 컴파일만 했고 Python은 AST만 확인했다.

## 후속 소비자 검토 범위

| 연결 | 다시 읽은 경로/범위 | 이번 결론 |
|---|---|---|
| 첫 시작/재진입 | App의 CBT 라우팅, CBT의 start/saveThought/open, recordsApi, 기록 confirm 서비스/상세 DTO | F1 단계 분리, 저장 생각 충돌 방지 |
| 재개/실패/종료 | OpenReflections 상세/재개, InsightTransactions 전체 명령, InsightService, LegacyReflectionRetry, 구형 QUESTION prepare/complete/fail | F2 구형/신형 job과 revision 연결; RESTORE에서 생성 없음 |
| AI 복원/누적 | cbt_simple contracts/service, 기존 pending USER/캐시 처리 | F2 입력은 현재 계약으로 전달; AI 의미 엔진 변경 없음 |
| 승인/구형·신형 상세 | InsightTransactions.confirm/cancel, ReflectionSessions.confirmedInsight/confirmInsight, CompletedReflection | AFTER와 전체 유형 거부 승인 의미 유지 |
| 보고서/PDF | WeeklyReportsService의 대상·집계, PdfExportService의 결과·문답 출력 | 승인 결과 및 구형/신형 구분 유지 |
| 검색/임베딩 | EmotionRecordsService의 자격/사례/설명, repository 유사 검색, embedding retry, pattern_explanation 및 응답 DTO | F3 AFTER 사례는 유지, 수락 유형만 집계 |
| 실제 화면 소비 | DailyCare 패턴 표시, EmotionRecordDetail 패턴 표시, OpenReflections 및 CBT | 빈 반복 유형과 도움이 된 생각의 동시 표시 유지 |

이 표는 이번 수정의 관련 범위를 확인한 기록이다. ChatGPT의 전체 브랜치 재리뷰 원장이나 런타임 통과 판정을 대신하지 않는다.

## 정적 확인과 보류 상태

- `git fetch origin fix/CBTAI`, `git rev-parse HEAD origin/fix/CBTAI`: 시작 SHA 일치. 최종 push/원격 대조는 delivery.json에 기록한다.
- JDK17 `gradlew.bat compileJava compileTestJava -x test --offline --no-daemon`: 성공, 20초. compileJava와 compileTestJava 실행, processResources UP-TO-DATE. JUnit/서버/DB 실행 없음.
- `node node_modules/vite/bin/vite.js build --outDir ../mindot_ai/artifacts/review-fixes-b7cc7cb/frontend-build`: 128 modules, 번들 생성 성공. 브라우저/E2E 실행 없음.
- `node --check`로 confirmThoughtForOpen.js 및 해당 테스트 소스 문법 확인.
- Python `ast.parse`로 pattern 테스트, pattern_explanation, CBT contracts/service 및 갱신한 runner의 문법 확인. 제품 모듈/모델을 실행하지 않았다.
- `git diff --check`: 공백 오류 없음. 제품 prompt 5개, model-contracts.json, distortion-definitions.json은 b7cc7cb git blob과 바이트 동일. 입력/출력/호출 예산, 채점표, 봉인 기대값 변경 없음.
- 리뷰 후 runner에는 새 pattern suite와 F1–F3 테스트 소스 선택을 연결했다. runner 자체도 실행하지 않았고 CHANGES_REQUIRED 상태로 테스트를 허용하지 않는다.

비차단 관찰 두 건은 별도로 유지한다: PDF의 페이지보다 긴 단일 문답 행 분할 제한, Writer 형식 복구가 실패 초안 없이 plan으로 재작성하는 점. 이번 F1–F3 수정에 포함하지 않았으며 canary 조건이나 AI 출력 제한을 늘리지 않았다.

실제 네트워크 장애·동시성·DB 재시작/제약·화면 상태 전이·모델 의미 품질은 미검증이다. 원격 push와 산출물 저장 후 멈추며, 최종 SHA에 대한 ChatGPT의 READY_FOR_TEST 이후에만 테스트 단계로 간다.
