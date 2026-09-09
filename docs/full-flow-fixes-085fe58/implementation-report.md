# 전체 기능 흐름 F5–F14 수정 제출

기준/시작 SHA: `085fe587afa7365e513c241073849cb69bcbe280`, 브랜치 `fix/CBTAI`. 시작 작업 트리는 깨끗했다. 최종 SHA는 이 문서를 포함한 제출 커밋과 전달 기록에 명시한다.

상태: `READY_FOR_GPT_FULL_BRANCH_REVIEW`. 테스트: `NOT_RUN_PENDING_GPT_FULL_BRANCH_REVIEW`. 사용자의 새 `Mindot-Full-Flow-Fixes-Codex-Prompt.md`를 실행 기준으로 삼았다. 별도의 최신 전체 흐름 리뷰 파일은 첨부되지 않아 이 문서의 F5–F14와 현재 코드, 기존 리뷰를 대조했다. 이전 F1–F3 산출물 재생성 작업은 중단했다.

## 항목별 상태와 근거

| 항목 | 상태 | 수정 파일과 동작 | 남은 실행 확인 |
|---|---|---|---|
| F5 | 수정 | `recordsApi.js`, `EmotionHistory.jsx`, `DailyCare.jsx`, `EmotionRecordsService.java`, `EmotionRecordsListPeriod.java`. Page의 content/totalElements/totalPages 사용. 기간·감정·상황·원문 검색·정렬·0-based 페이지를 서버에 전달. 필터 변경은 첫 페이지, 늦은 응답은 effect 종료 여부로 차단, 사라진 마지막 페이지는 유효 페이지로 재조회. 강도 정렬은 Criteria CASE로 미입력을 마지막에 배치. DailyCare는 전체 최신 1건과 최근 7일 전체 건수를 별도로 조회. | 다중 페이지·빠른 검색 변경·삭제 후 마지막 페이지·미입력 강도 정렬 |
| F6 | 수정 및 실제 DB 카탈로그 확인 | `AiJobOperationConstraintInitializer.java`, `db-catalog.json`. 기존 CBT_COMMAND 보완을 유지하고 실제 누락된 SAFETY_STOPPED를 같은 원칙으로 추가. 기존 CHECK 식을 보존하는 OR 확장. CHECK가 없거나 이름이 다르면 성공으로 넘기지 않고 시작 실패로 명시. | 새 코드의 DDL 실행은 보류. 새/기존 DB, 이름 변경, 권한·잠금 실패, 재기동 확인 필요 |
| F7 | 수정 | `EmotionRecordAiTransactionService.java`, `EmotionRecordsService.java`. 기록 잠금 → 최신 STRUCTURE 조회 → 진행 중이면 재사용. 완료는 같은 잠금 아래 작업 종류·소유자·기록·최신 ID·deadline·QUICK 여부를 검증. COMPLETE, 만료·실패·교체 작업에는 반영하지 않음. AI 호출뿐 아니라 결과 커밋 실패도 별도 트랜잭션으로 FAILED 처리. 확정·시각 변경·삭제도 기록 잠금 사용. | A/B 역순, 확정 직후 늦은 결과, 삭제 교차, 만료 후 재시도와 커밋 실패 |
| F8 | 수정 | `EmotionRecord.jsx`, `recordsApi.js`, `EmotionRecordsController.java`, `EmotionRecordsQuickCreateResponseDto.java`, 원문 트랜잭션·AiJobs/Users repository. Idempotency-Key를 최초 저장 전에 사용자 잠금 아래 조회. 같은 키와 다른 내용은 409. 같은 요청은 동일 기록으로 복귀하고 AI를 중복 호출하지 않음. 키·원문·발생 시각을 화면의 재전송에 유지. AI 실패에도 저장 ID와 analysisStatus/analysisErrorCode 반환. 응답 구조화 값은 현재 저장 상태에서 읽음. 새 기록 작성/편집은 새 논리 요청이므로 같은 문장도 별도 저장 가능. | 저장 응답 유실·연속 클릭·원문 저장 후 분석/커밋 실패·같은 문장 별도 기록 |
| F9 | 수정 | `automaticThought.js`, `CBT.jsx`, `EmotionRecordDetail.jsx`, `EmotionRecordsConfirmRequestDto.java`. 한국어 종결·어절 정규식 제거. 두 진입 화면에서 공통 공백 검사와 기존 CBT 입력창의 4,000자 한도 사용. 확인 요청 DTO도 동일 문자 상한. 의미 판단 모델/프롬프트 변경 없음. | 짧은 생각·질문형·영어·한국어·공백·길이 경계 |
| F10 | 수정 | `WeeklyReportsService.java`, `WeeklyReport.jsx`, `PdfExportService.java`. 해당 주 감정 기록과 완료 CBT가 모두 없을 때만 거절. 감정 평균의 기존 null 계산 유지, UI는 기록 없음 표시. 감정 발생일/CBT 완료일 기준을 화면에 명시. PDF는 감정 발생일로 선택한 기록의 연결 CBT라는 기존 기준을 유지하고 PDF 본문에도 설명. | 감정만/CBT만/둘 다/둘 다 없는 주와 PDF 대상 차이 |
| F11 | 수정 | `CbtSimilaritySearchRequest.java`, `RagUtils.java`, `ReflectionSessionsRepository.java`, `EmotionRecordsService.java`. 현재 recordId를 전달해 두 벡터 SQL에서 LIMIT 이전에 제외. 전체 자격의 완료 수·서로 다른 날짜 수·도움 점수도 현재 기록을 제외. F3의 전체 유형 거부 AFTER 자격 유지. | 자기만 있는 경우, 자기+다른 사례, 제외 후 topK 채움, 반복 유형 근거 수 |
| F12 | 수정 | `EmotionRecordsService.java`, `ReflectionSessions.java`, `ReflectionSessionEmbeddingTransactionService.java`, `EmbeddingRefreshRequested.java`, `InsightEmbeddingService.java`. 실제 입력에 쓰는 timeBucket이 달라지면 벡터 무효화·이전 실행 실패 표시 후, 커밋 이후 기존 비동기 EMBED 재시도 경로로 생성. 같은 bucket의 날짜/시각 이동은 재생성하지 않음. 작업에 정확한 입력 문구를 저장하고 완료 시 최신 job ID·deadline·현재 문구와 비교. 기록 → 세션 순서로 잠금, 초기 ID 조회는 scalar로 하여 잠금 전 오래된 엔티티 캐시 사용 방지. | 수정/완료 교차, A→B→A 이동, 실패 후 수동 재시도, 삭제 교차 |
| F13 | 수정 | `PdfExportService.java`. 문답/정보 행에 공통 줄 단위 페이지 분할 적용, 다음 페이지에 화자/항목과 이어짐 표시. Unicode code point 단위 폭 계산. 지원하지 않는 글자는 정확한 `[U+코드]`로 명시하며 원문/DB는 변경하지 않음. BEFORE·AFTER 및 일반 본문도 같은 문자 처리 사용. | 60줄 처음/중간/끝, 혼합 한글·영문·이모지, 긴 정보 행의 실제 PDF 렌더링 |
| F14 | 수정 | `WeeklyReport.jsx`, `DailyCare.jsx`. 감정 강도 /10, 도움 점수 /5. 상세·목록·CBT·PDF의 기존 /10을 대조. 저장값 재계산 없음. 활동 추천의 임계값 4 유지. | 0/4/8/10/null 표시 대조 |

## 기존 DB에서 실제 확인한 정의

`db-catalog.json`은 이 작업에서 JDBC 읽기 전용 트랜잭션으로 시스템 카탈로그만 조회한 결과다. 개인정보·원문 행은 조회하지 않았다. 조회 후 rollback. 조회 대상은 ai_jobs/reflection_sessions/emotion_records/users의 CHECK·UNIQUE·FK와 필요한 컬럼이다.

- `public.ai_jobs_operation_check`: 기존 STRUCTURE/QUESTION/DISTORTION_CLASSIFY/EMBED/PATTERN_RAG/REPORT_GENERATE/SAFETY_CHECK 식과 `OR CBT_COMMAND`가 실제 존재하고 validated=true였다.
- `reflection_sessions_status_check`: 실제 허용값은 OPEN/COMPLETED/CANCELLED였다. **SAFETY_STOPPED가 없었다.** 이번 코드에 이를 확장하는 보완을 추가했으며 실제 DB에는 이 작업에서 적용하지 않았다.
- ai_jobs의 request_payload/response_payload는 nullable jsonb, attempt_deadline은 nullable timestamptz였다.
- ai_jobs UNIQUE는 `(entity_type, entity_id, operation, idempotency_key, attempt_no)`, reflection_sessions에는 emotion_record_id UNIQUE가 존재했다.
- 사용자 FK와 reflection_sessions→emotion_records FK의 ON DELETE CASCADE를 확인했다. ai_jobs.entity_id는 다형 참조로 해당 기록에 직접 FK가 없으므로 작업/기록 연결 검사를 코드에서 수행한다.
- 다른 DB, 다른 스키마, 이름이 다른 CHECK는 이 조회의 확인 범위가 아니다. 스키마 초기화·데이터 변환·새 migration 프레임워크는 추가하거나 실행하지 않았다.

## 실제 소비자와 이전 보완 보존

원문 저장은 EmotionRecord → recordsApi → quick controller → 짧은 저장 트랜잭션 → 트랜잭션 밖 분석 → 별도 결과/실패 트랜잭션 → 저장 상태 응답으로 연결했다. 재분석이 아직 QUICK이면 상세 화면에서 성공한 분석으로 안내하지 않는다. 재조회와 저장 응답의 안전 안내도 유지했다.

현재 임베딩 입력은 상황 범주·상황·감정·처음 생각·timeBucket이다. occurredAt 날짜/weekdayType 자체는 입력이 아니므로 bucket이 같을 때 재생성하지 않는다. 변경된 시각은 리포트 캐시를 기존 방식으로 무효화한다. AFTER·유형 검토·대화 내용은 수정하지 않는다.

F1의 확정 이후 OPEN 전용 재시도와 멱등 키 유지, F2의 구형 QUESTION 연결 및 늦은 결과 차단, F3의 유형 수락과 AFTER 사례 자격 분리를 보존했다. F4로 이어진 감정 기록 상세 GET 복원(`9be16f6`)도 유지했다. `InsightTransactions`, `LegacyReflectionRetry`, `confirmThoughtForOpen`, `PatternSimilarCaseDto`, AI의 패턴 소비는 이번 변경에서 의미를 바꾸지 않았다.

Agent/Writer/Assessor/같은 Agent 검토, 원문 메모리, 사용자 승인 경계는 변경하지 않았다. AI 제품 파일·프롬프트·model-contracts·정의·평가 기대값은 기준 SHA와 동일하다. 나중에 이어하기는 OPEN, 완전 종료는 CANCELLED다. 기존 긴 단일 PDF 행 관찰은 F13으로 수정했으며 Writer 형식 복구 방식은 범위 밖으로 유지했다.

## 실제 실행과 검증 한계

- `git status --short`, `git rev-parse HEAD`, 원격 fetch/조회: 시작 SHA/깨끗한 작업 트리 확인. 동료 변경/기존 커밋을 되돌리지 않는다.
- JDK17 `gradlew.bat compileJava compileTestJava -x test --offline --no-daemon`: 컴파일. 첫 확인에서 repository 메서드의 불필요한 인자를 발견하여 수정했고 이후 성공했다. 실제 JUnit 실행은 없다.
- `node node_modules/vite/bin/vite.js build --outDir ../mindot_ai/artifacts/review-fixes-b7cc7cb/full-flow-frontend-build`: 129개 모듈 번들 생성 성공. 브라우저 실행 없음.
- `node --check` automaticThought.js/recordsApi.js: 문법 확인. `git diff --check`: 공백 오류 없음.
- ReadCatalog.java와 기존 PostgreSQL JDBC 드라이버로 읽기 전용 카탈로그 SELECT 1회. 연결 정보는 출력/커밋하지 않음. 실제 정의와 판단을 db-catalog.json에 기록.
- 모델 호출, canary, 오프라인 동작 테스트, 서버 실행, DB DDL 실행, PDF 생성·렌더링은 모두 하지 않았다. 컴파일/소스 추적을 동작 검증이나 모델 질문 품질의 증거로 간주하지 않는다.

다음 단계는 최종 원격 SHA에 대한 ChatGPT 전체 기능 흐름 재리뷰다. 자체 READY_FOR_TEST 판정은 하지 않는다.
