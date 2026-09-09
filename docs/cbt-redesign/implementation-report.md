# CBT insight 전체 스택 구현 보고서

구현 단계: 원격 push 및 ChatGPT 전체 브랜치 리뷰 제출용. 이 문서는 ChatGPT의 리뷰 판정이나 테스트 승인 보고서가 아니다.

- 저장소/브랜치: JephyrWing/project_mindot / fix/CBTAI
- 시작 로컬/원격 SHA: `92618c194e8a70a13a8913425b5d396aea359e10`
- push 직전 재fetch에서도 원격 SHA 동일. 기존 기록 목록·페이지·정렬·소셜 로그인 변경을 보존했다.
- 최종 SHA, remote 대조, ZIP bytes/hash는 저장소 밖 `delivery.json`에 기록한다. 자기 커밋 SHA를 같은 커밋의 문서에 순환 삽입하지 않는다.
- 모든 테스트: `NOT_RUN_PENDING_GPT_FULL_BRANCH_REVIEW`.
- 입력 패키지 SHA256: `c5e988ecdb51c609c2623dd900c0147ccdcc0bfb497f6d087796c704a3814838` (413693 bytes, manifest 51개 검증).
- 최신 iteration log: Drive `1SEXzKZPNTJzuzjrcjp8hiyBnMHU4oetu`, modifiedTime `2026-09-09T09:56:59.579Z`. 최신 전체 스택 결정까지 읽었다. fetch된 텍스트를 UTF-8/LF로 저장한 SHA256 `72d82f5671ed1ef163e50df6788e0d96e109114b7b90cd358c4b528f417b9d12` (Drive 원본 바이트의 해시라고 주장하지 않음).
- 봉인 key/평문은 열지 않았다. 평가 expected/gold/caseId를 제품 입력에 추가하지 않았다.

## 현재 활성 흐름

React `App.jsx` → `CBT.jsx`/`OpenReflections.jsx` → `reflectionsApi.js` → Spring `ReflectionSessionsController` → `InsightService` → `InsightTransactions`의 입력 저장 → `InsightAiClient` → FastAPI `app.py` → `cbt_session_agent.py` → `cbt_simple/service.py` → 실제 LangGraph `graph.py` → `provider.py`/`wire.py` → Spring 결과 저장 → `SessionView`.

Spring 트랜잭션 객체에는 AI client가 없다. prepare/complete/fail이 각각 짧은 트랜잭션이며 service의 네트워크 호출을 감싸지 않는다. 기존 `AiJobs`의 nullable request_payload/response_payload/attempt_deadline과 `CBT_COMMAND`로 논리 요청, 전송 시도, 만료, 저장된 응답을 관리한다. 기존 reflection_sessions.ai_meta.insight에 revision/phase/제안/확정 결과를 보관한다.

실제 AI 도구는 write_turn / assess_completion / respond_control / respond_safety 네 개다. 제공된 5개 프롬프트와 model-contracts.json을 실제 provider에 연결했다. Writer와 Assessor는 도구 노드에서 호출하고, 완료 후보는 같은 Agent 객체에 실제 도구 호출과 대응 ToolMessage를 전달해 검토한다. 일반 2회, 완료 3회, Writer 형식 복구 포함 최대 3회를 호출 경계에서 제한한다. 자동 SDK retry=0. 별도 coverage/GOAL/정정 장부와 Assessor 보충 질문 경로는 활성 엔진에서 제거했다.

RESTORE는 provider를 만들지 않는다. NEW/턴은 입력과 논리 job을 먼저 저장한다. 메모리 유실은 모델 호출 전 RESYNC_REQUIRED → full RESTORE → 같은 delta 한 번으로 연결한다. RESTORE에 이미 담긴 pending USER는 다시 append하지 않는다. 성공한 생성의 메모리 캐시는 재시도 attempt 번호만 전송 봉투에서 연결하고 생성된 메시지는 그대로 반환한다.

DB 중복 확인은 소유권 확인 다음, If-Match 이전이다. 같은 키/다른 본문은 충돌이다. 성공 영수증은 저장된 view를 반환한다. retry는 새 사용자 답변을 받지 않고 저장한 동일 입력을 재전달한다. 만료된 job은 조회/명령 진입 시 FAILED로 전환한다. 결과 저장은 active job, attempt, revision, session status와 deadline을 재확인한다. 취소는 DB 상태를 먼저 닫고 runtime 정리는 비동기로 수행한다. 늦은 생성은 저장하지 않는다.

AFTER는 실제 USER 인용의 존재를 한 경계에서 검사한 후보만 동일 Agent 의미 검토로 넘긴다. null AFTER는 승인 불가 UNRESOLVED로 안내하고 기술 실패나 왜곡 없음으로 바꾸지 않는다. 제안은 BEFORE/AFTER/이유/유형/근거를 한 번에 표시한다. EXPLAIN_PROPOSAL은 같은 제안과 ID를 유지한다. 다른 질문/도움/철회는 이전 제안을 보관하고 활성 승인 대상을 비운다. confirm은 최신 If-Match와 활성 proposalId를 확인하며 자연어 동의를 승인으로 처리하지 않는다. 모든 유형 거부와 빈 유형 제안도 허용한다.

## 필드별 생산·저장·소비 지도

아래 경로는 각 앱 루트 기준이다. 전체 소스 manifest가 정확한 경로와 blob hash를 제공한다.

| 필드 | 생산/검사 | 영구 저장·공개 API | 화면·후속 소비자 |
|---|---|---|---|
| messages/messageNumber | AI contracts/service, Spring InsightMapping | 기존 question_answers에 새 flat row append, SessionView | CBT, OpenReflections, PDF; 전체 대화가 Writer/Assessor에도 전달 |
| beforeText/beforeCorrection/afterText | Assessor → state.candidate_boundary → 같은 Agent 검토 | ai_meta.insight.currentProposal → confirm → confirmedResult, 기존 outcome 필드 호환 저장 | InsightResult, CompletedReflection, WeeklyReport, PdfExportService, PatternSimilarCaseDto |
| suggestions/reviews | Assessor의 기존 12개 코드/원문 근거 | 확인 시 전체 코드 일치 검사, SessionDistortions BEFORE 검토 상태 및 confirmedResult | InsightResult 수락/거부, WeeklyReportsService CONFIRMED_INSIGHT, RAG 수락 코드 |
| status/phase/revision | InsightTransactions만 영구 변경 | SessionView, ETag/If-Match, 목록 DTO phase/revision | App 기존 URL·라우터, CBT, OpenReflections; 닫힌 세션 재개 차단 |
| currentProposal/confirmedResult | AI 승인 후보, Spring 최종 사용자 확인 | pending/failed 중 승인 숨김, priorProposals 보관; COMPLETED+userConfirmed만 confirmedResult 반환 | 동일 제안 설명, 한 번 확인, 완료 상세·주간·PDF·검색 |
| requestId/attemptNo/deadline | Spring AiJobs 입력 저장 | 동일 키 결과 캐시, 재시도·만료·늦은 결과 억제 | AI ProtocolError, InsightService resync, React jobId+revision 역전 방지 |

## 공개 경로 및 기존 소비자

- `POST /api/reflections/open`: emotionRecordId 또는 sessionId. 기존 세션은 표시/RESTORE만 수행한다. 신규 질문이 처리 중이면 저장된 진행 view를 반환한다.
- `GET /api/reflections/{sid}`: DB 정본 조회, 만료 job 복구. `GET /api/reflections/open`: 기존 목록 기능 유지, 새 phase/revision 제공.
- `POST /{sid}/turn`: `{answer}`만 새 발화. `POST /{sid}/retry`: 본문 없음.
- `POST /{sid}/confirm`: proposalId, 모든 유형 리뷰, 점수. 클라이언트가 AFTER를 덮어쓰는 필드는 없음.
- `POST /{sid}/cancel`: 기존 완전 종료 확인창에 연결. 나중에 이어하기는 OPEN을 유지하는 화면 이동.
- `POST /{sid}/retry-embedding`: 기존 완료 결과의 검색 연결 재시도 유지. 임베딩 실패가 확정 결과를 되돌리지 않는다.
- 구형 /start, /answer, 개별 첫/다음 질문 재시도 등은 새 controller에서 등록하지 않는다. 구형 Java service/DTO와 AI의 cbt_agent/cbt_q11/cbt_q10 등은 역사 소스로 남지만 새 CBT 실행의 대체 경로로 호출하지 않는다. cbt_q11의 JSON 파서·토큰 계산·진단·기존 좁은 안전 탐지 유틸만 재사용한다.

기존 question_answers는 배열의 기존 시간순과 질문/답변 타임스탬프로 read mapping하며 미답변 질문도 보존한다. 동시각에는 기존 저장 순서를 사용한다. 구형 행을 일괄 덮어쓰지 않는다. 구형 완료 결과는 legacyResult로 표시하고 새 AFTER로 소급 재분류하지 않는다. 구형 OPEN은 원문 문답과 과거 유형 검토를 복원해 새 대화를 진행하며, 구형 대안 문구를 새 승인 가능한 insight 제안으로 자동 변환하지 않는다.

기록 생성/수정/삭제·정렬/페이지·음성·로그인·라우팅의 기존 구현을 읽어 연결을 확인했다. 새 공개 API의 모든 현재 React 호출처는 CBT/OpenReflections/CompletedReflection/WeeklyReport에서 추적했다. JWT 소유권과 역할 보호, 기록 검색의 사용자 ID 조건, COMPLETED+userConfirmed 조건은 유지했다. 기록 분석 `/internal/ai/records`는 기존 분석 경로를 유지한다.

주간 보고서는 새 수락 유형을 CONFIRMED_INSIGHT로 집계하고 구형 결과만 REMOVED/PERSISTED/NEW 차집합에 남긴다. 구형 저장 report snapshot은 수정하지 않는다. PDF는 새 BEFORE/AFTER/수정 이유/유형별 수락·거부와 새 문답 행을 읽는다. 전후 확신도 모두 같은 초기 생각을 대상으로 표시한다.

RAG 검색은 기존 context / context+thought 두 임베딩 목적을 유지하고 확정한 BEFORE를 사용한다. 새 PatternSimilarCaseDto는 confirmedResult와 resultFormatVersion을 함께 전한다. 기존 Spring 패턴 client의 `/internal/ai/patterns/explain`에 FastAPI 구현이 없던 연결 누락도 보완했다. 추가 분류 LLM 대신 검색된 승인 결과에서 반복 수락 코드와 도움이 됐다고 평가한 실제 생각만 표시하는 결정적 요약이다. 현재 기록에 왜곡 유형을 새로 부여하지 않고 구형 대안 문구는 구형으로 명시한다. 이 표시 방식은 후속 리뷰 대상이며 새로운 모델 품질을 검증했다고 주장하지 않는다.

## 설정·정적 확인

provider 호출 30초, 제품 생성 180초, Spring CBT read 195초, DB attempt deadline 210초, nginx 225초, 브라우저 240초, AI idle TTL 600초. moderation 15초/요청당 1회이며 사용 불가를 위험으로 치환하지 않는다. CORS는 Idempotency-Key/If-Match를 허용한다. 운영 compose에서 FastAPI는 backend와 별도 ai 네트워크를 공유하며 frontend가 직접 호출하지 않는다. AI Docker context에서 artifacts/tests/.env.*를 제외한다. 배포 workflow는 여전히 main push만 대상이며 fix/CBTAI push는 배포를 트리거하지 않는다.

실행한 주요 정적 명령과 실패/복구:

1. `git diff --check`: 최종 공백 오류 없음.
2. JDK17 `gradlew compileJava -x test --offline --no-daemon`: 최초 Gradle 배포판 미준비/샌드박스 네트워크 오류. 일반 compileJava로 Gradle/의존성 준비 후 성공. 서버나 DB는 실행하지 않았다.
3. `gradlew compileJava compileTestJava -x test --offline --no-daemon`: 기존 UsersControllerAuthTest의 새 소셜 로그인 생성자 3개 및 userRole 누락으로 첫 실패. 제품 인증 동작 수정 없이 해당 테스트 fixture를 현재 DTO에 맞춘 뒤 Java 및 테스트 소스 컴파일 성공. JUnit 실행 아님.
4. `node node_modules/vite/bin/vite.js build`: 기존 dist 폴더 정리 권한 오류. 별도 artifacts/full-stack-redesign-20260909/frontend-build-review 출력 폴더를 지정해 최종 127 modules 번들 생성 성공. UI/E2E 실행 아님.
5. `python mindot_ai/tools/insight_static_check.py .../static-results.json`: AST 14개 Python 소스, strict schema의 필수 키/추가 키 구조, 실제 pure wire 직렬화 확인. 최초 공개 tokenizer 테이블 캐시 미준비로 실패 후 공개 o200k 테이블을 준비해 성공. 제품 SDK client/모델은 만들지 않았다.

입력 추정 한도 48000 tokens / 직렬화 196608 bytes, 출력 cap SELECT8192/WRITER650/ASSESSOR1800/REVIEW1200/REPAIR650 유지. 대표 SELECT 요청은 초기 1826 tokens/7599 bytes, 20문장 5806/27800, 80문장 17746/88430. 각 단계의 전체 수치는 별도 static-results.json에 있다. 실제 SDK wire 추가 필드, 모델 수용, usage는 미검증이다. 이 측정은 입력 전체 한도가 매 요청의 실제 비용이라는 뜻이 아니다.

## 작성했지만 실행하지 않은 검사와 남은 검증

- Python `tests/test_insight_protocol.py`: 실제 LangGraph 노드 + scripted provider fixture로 RESTORE 0호출, pending USER 중복 방지, 성공 캐시 attempt 연결, 동일 키 다른 본문 충돌, 제안/설명/철회, null AFTER 경로를 검증하도록 작성.
- Spring InsightServiceTest/InsightMappingTest: 저장 결과 재전달, resync 순서, 임베딩 분리, 구형/새 행 read mapping. 서버/DB 없는 fixture 소스이며 컴파일만 했다.
- Frontend reflectionsApi.test.js/sessionView.test.js: 동일 key/body/revision 재전달, 무본문 retry, 모든 유형 거부, 이전 attempt poll/취소 후 응답 역전 방지.
- `tools/insight_review_runner.py`: 저장소 밖 실제 ChatGPT 리뷰의 READY_FOR_TEST, reviewedCommitSha, read-manifest hash와 local/remote SHA를 대조한 뒤 선택한 offline suite만 실행하도록 준비. 이번에는 이 runner도 실행하지 않았다. 전체 DB/브라우저 동시성은 제공한 offline-scenarios로 후속 검증한다.
- canary 12개 및 공식 비교의 계획/한도는 제공본으로 보존했다. 현재 runner는 offline suite 진입용이다. live Spring fixture provisioning 및 공식 baseline/candidate 수집은 후속 리뷰의 nextTestScope와 숫자 예산 잠금에 따라 기존 수집 도구의 adapter 연결을 확인해야 한다. 현재 수집/점수/XLSX 없음.
- 실제 PostgreSQL DDL은 읽거나 실행하지 않았다. 프로젝트의 기존 Hibernate ddl-auto:update 관리 방식에 맞춘 nullable column 추가다. 운영 DB의 기존 enum CHECK가 CBT_COMMAND를 허용하는지, nullable JSONB/attempt_deadline 생성·권한·잠금 동작은 운영 DB 관리 방식으로 확인해야 한다. migration 도구/일괄 이관/데이터 삭제는 추가하지 않았다.
- 실제 OpenAI schema 수용/질문 품질/AFTER 의미, timeout·DB 재시작·동시 confirm/cancel·프로세스 유실, PDF 레이아웃, 브라우저 렌더링과 접근성, 임베딩과 패턴 요약의 실제 사용자 동작은 미검증이다.
- 이 보고서는 모든 미수정 소스를 줄 단위로 리뷰했다는 원장이 아니다. 전체 tracked manifest와 전체 소스를 제공하며 다음 행동은 ChatGPT가 최종 원격 SHA 전체를 읽고 자기 read manifest와 판정을 남기는 것이다. Codex가 자체 승인 후 테스트로 진입하지 않는다.
