# Mindot CBT 전체 브랜치 리뷰

**판정: CHANGES_REQUIRED — 테스트 전 수정 3건.**

- 저장소: `JephyrWing/project_mindot`, 브랜치: `fix/CBTAI`
- 검토 커밋: `b7cc7cba4bf33ecc7361e32c06bde834206cd3ad`
- 구현 시작 커밋: `92618c194e8a70a13a8913425b5d396aea359e10`
- 검토일: 2026-09-09 UTC
- 방법: 원격 커밋을 고정한 전체 소스 정적 검토. 제품 수정·테스트·모델/API 호출은 실행하지 않았다.

## 결론

핵심 구조를 다시 폐기할 근거는 찾지 못했다. Spring의 영구 이력과 AI 세션 메모리, 실제 Agent와 도구 호출, 사용자의 인식 변화에 근거한 AFTER 제안, 사용자 승인 후 저장은 합의한 방향으로 연결돼 있다. 다만 첫 시작의 실패 복구, 구형 세션의 저장된 답변 재시도, 확정된 AFTER의 검색 소비에 기능 단절이 있다. 세 가지를 고치고 새 커밋을 다시 리뷰한 다음 테스트로 넘어간다.

아래 실패 경로는 코드상 조건을 따라 확인한 결과다. 실제 운영 DB에서 해당 레코드가 존재한다거나 이번에 canary가 실패했다는 뜻은 아니다. 모델의 질문 품질과 실제 의미 판단도 아직 검증하지 않았다.

## 수정 필요 사항

### F1 · P2 · 최초 자동사고 저장 후 OPEN 실패 시 같은 화면의 재시도가 막힘

근거:

- `mindot_front/src/components/CBT/CBT.jsx:103–114` — `confirmEmotionRecord` 이후 `OPEN`을 호출하고, OPEN이 성공해야 `setRecord(null)`을 수행한다.
- `mindot_back/src/main/java/com/my/mindot_back/records/service/EmotionRecordsService.java:316–337` — 기록 확정은 PARTIAL 상태만 허용한다.

도달 경로: 자동사고가 없는 PARTIAL 기록에서 시작 → 사용자가 생각을 입력 → 기록 확정 성공으로 COMPLETE 전환 → OPEN 요청의 네트워크 실패 → 기존 입력 화면 유지 → “저장하고 시작하기” 재클릭 → 이미 COMPLETE인 기록을 다시 확정하려 하므로 409. 기록 자체는 저장돼 있지만 사용자는 같은 화면에서 시작을 정상 재시도할 수 없다.

수정: 기록 확정 단계와 세션 OPEN 단계를 분리하여, 확정 성공 이후에는 같은 OPEN 요청만 재시도한다. 확정 응답 자체가 유실된 경우에는 기록 상태와 저장된 생각을 다시 조회해 어느 단계까지 완료됐는지 복구한다. COMPLETE 기록을 무조건 덮어쓰거나 모든 409를 성공으로 취급하는 방식은 사용하지 않는다. OPEN의 기존 멱등 키는 유지한다.

후속 확인: DailyCare에서 PARTIAL 기록을 여는 경로, 최초 화면, 새로고침·재진입, OPEN 응답 유실 시 중복 세션 방지. 현재 유틸리티 수준 검사는 이 화면 상태 전이를 대신하지 못한다.

### F2 · P2 · 구형 OPEN 세션의 저장된 답변 뒤 생성 실패를 새 RETRY가 복구하지 못함

근거:

- `mindot_back/src/main/java/com/my/mindot_back/records/service/ReflectionSessionTurnTransactionService.java:42–95,194–201,204–285` — 구형 경로는 답변을 먼저 저장하고 QUESTION job을 만든다. 생성 실패 후 저장된 답변을 사용하는 재시도 기능이 있었다.
- `mindot_back/src/main/java/com/my/mindot_back/records/service/InsightMapping.java:20–30` — 구형 질문·답변은 새 메시지 이력으로 변환된다.
- `mindot_back/src/main/java/com/my/mindot_back/records/service/InsightTransactions.java:36–38,99–126,146–155,236–247` — 새 job 조회는 insight의 `lastJobId`만 사용한다. 기존 이력이 있는 OPEN은 복원 영수증을 만들지만 구형 실패 job을 새 재시도 상태에 연결하지 않는다. previous가 null이면 RETRY는 409다.

도달 경로: 구형 OPEN 세션에서 사용자 답변 저장 성공 → 다음 질문 생성 실패 → 이번 구현으로 재개. 문답은 보이지만 job이 없는 것으로 표시되어 “생성 다시 시도”가 나오지 않는다. RETRY 직접 요청도 거부되고, TURN은 추가 답변을 요구하게 된다. 이력 복원만으로는 기존 재시도 기능까지 복원되지 않는다.

수정: 구형 저장 상태와 job을 읽는 작은 호환 처리를 둔다. 저장된 마지막 사용자 답변에 대한 생성이 실제로 실패했거나 아직 처리 중인지 확인하여 새 화면·재시도 흐름에 연결한다. 동일 답변을 새 USER 메시지로 다시 추가하지 않는다. RESTORE만으로 모델을 호출하지 않으며, 명시적 재시도가 해당 답변을 한 번만 처리하도록 복원 이력과 delta 경계를 맞춘다. 단순히 이력 마지막이 USER라는 이유만으로 실패로 판정해서는 안 된다. 구형의 성공한 제안 상태와도 구별해야 한다.

후속 확인: 구형 정상 재개, 구형 실패/진행 중 job, 구형 제안이 있는 세션, 새 세션의 실패 재시도, 만료·늦은 응답 차단. 구형 엔진을 다시 활성화하거나 DB 일괄 변환·마이그레이션 도구를 도입할 필요는 없다.

### F3 · P2 · 인지왜곡 유형을 모두 거부한 유효한 AFTER가 유사 사례 검색에서 탈락함

근거:

- `mindot_back/src/main/java/com/my/mindot_back/records/service/EmotionRecordsService.java:484–501` — 새 confirmedResult와 수락 유형을 읽지만, 마지막에 `confirmedDistortionCodes`가 빈 사례를 전부 제거한다.
- 같은 파일 `:535–539` — 남은 사례가 없으면 패턴 설명 요청이 409로 끝난다.
- `mindot_ai/pattern_explanation.py:29–54` — 수락 유형이 없는 경우도 처리하며, 확정 AFTER의 도움 여부와 반복 유형 집계를 별도로 다룬다.

도달 경로: 사용자가 AFTER를 승인하되 제안 유형은 전부 거부 → 정상 COMPLETED 저장 → 도움 점수·소유권·날짜 등 검색 자격을 충족해도 수락 유형이 없다는 이유만으로 후보 삭제. 반복 인지왜곡을 계산할 근거가 없다는 사실이, 도움 된 바뀐 생각까지 재사용하지 못하는 결과로 이어진다. 유형 없음/판단 불충분 결과도 같은 영향을 받는다.

수정: 유효한 사용자 확정 AFTER를 가진 사례의 자격과 반복 유형 집계를 분리한다. AFTER 사례는 기존 소유권·완료·도움 점수·날짜 조건을 유지하여 전달하고, 반복 유형 집계만 수락된 코드로 계산한다. 없는 유형을 채우거나 거부된 유형을 복권하지 않는다. 구형 사례는 구형 의미를 유지한다.

후속 확인: DailyCare와 기록 상세의 “도움 된 생각”, 반복 유형 없음 표시, 임베딩/검색, 수락·부분 거부·전체 거부 혼합 사례. 저장·상세·보고서에서 보이는 AFTER가 검색 단계에서만 사라지지 않아야 한다.

## 큰 구조 및 수정하지 않은 소비자 검토

| 기능 연결 | 소스 검토 결과 |
|---|---|
| 인증·소유권·기록 CRUD | 사용자 소유권 검사와 원래 기록 흐름을 추적했다. 이번 리뷰에서 추가 차단 결함은 확인하지 못했다. |
| 시작·재개·TURN | NEW/RESTORE 전체 이력과 TURN delta, 실제 USER/ASSISTANT 순서를 확인했다. F1·F2의 복구 연결은 수정해야 한다. |
| AI 메모리 유실 | typed RESYNC_REQUIRED와 복원 후 동일 입력 처리 경로가 있다. 실제 프로세스 재시작 검증은 남아 있다. |
| 일반 질문·도움·정정 | 활성 경로는 app → facade → cbt_simple service/graph다. 구형 ledger/gap 처리 엔진을 런타임으로 되살리지 않았다. |
| Agent/Writer/Assessor | 실제 LangGraph Agent와 도구, 같은 Agent의 최종 검토가 연결돼 있다. ordinary 2회, completion 3회 및 복구 포함 3회 상한을 코드로 확인했다. |
| AFTER·제안·설명 | 인식 변화에 근거한 AFTER 계약이 프롬프트에 반영돼 있다. 설명 뒤 기존 제안을 승인할 수 있으며, 변화가 없을 때 억지 완성을 강제하지 않는다. 의미 판단의 성능은 미검증이다. |
| 확인·유형 거부 | AFTER와 제안 검토 결과 및 점수의 원자적 확정 경로가 있다. 전체 유형 거부도 허용한다. 검색 소비의 F3는 별도 수정 대상이다. |
| 나중에 이어하기·완전 종료 | OPEN 유지와 CANCELLED 종료가 구별된다. COMPLETED/SAFETY_STOPPED 및 취소 뒤 늦은 결과 차단을 추적했다. |
| 중복·timeout·실패 | 입력 선저장, 요청 멱등성, revision/attempt/deadline 검사와 결과 확정을 확인했다. UI 복구와 구형 job 연결에는 F1·F2가 남는다. |
| 목록·상세·이어하기 목록 | 새 결과와 구형 결과의 표시 경로, React 세션 key 및 목록 소비를 확인했다. |
| 보고서·PDF·임베딩 | 확정된 BEFORE/AFTER·수락 유형 소비로 연결된다. 구형 라벨 차집합을 새 AFTER 의미로 사용하지 않는다. PDF의 긴 단일 메시지 처리는 아래 별도 관찰 사항이다. |
| 기타 AI·배포·공용 설정 | 기록 분석과 패턴 설명, DTO/enum/의존성, proxy timeout을 읽었다. fix 브랜치 push가 main 배포 workflow를 실행하는 구조는 아니다. |

## 새 데이터의 생산·저장·소비 연결

아래 경로는 저장소 루트 기준이다. 각 디렉터리 내부 전체 코드를 읽고 연결을 추적했으며 파일별 원장은 `read-manifest.json`에 있다.

| 필드 | 생산·판단 | 저장·조회 | UI 및 후속 소비 |
|---|---|---|---|
| afterText | `mindot_ai/cbt_simple/graph.py`, assessor 및 assessment-review 프롬프트 | `InsightTransactions.java`, `ReflectionSessions.java`의 확정 결과 | `CBT.jsx` 승인 표시 → 기록 상세·주간 보고서·`PdfExportService.java`·`EmotionRecordsService.java` 검색 |
| suggestions / reviews | Assessor 후보 → Agent 검토; `CBT.jsx` 사용자 유형 검토 | `InsightTransactions.java`의 CONFIRM 및 confirmedResult | 수락 유형만 상세·보고서·패턴 집계로 전달; F3 확인 |
| status / phase | Spring 명령 및 AI 결과 종류 | `InsightTransactions.java`, `ReflectionSessions.java` | `CBT.jsx`, 이어하기 목록, 기록 상세, 보고서 대상 판정 |
| revision | Spring 입력 추가·결과 전이 | insight 상태, job 입력·시도 정보 | React 요청 revision 및 FastAPI NEW/RESTORE/TURN 메모리 일치 검사 |
| currentProposal | Agent가 수락한 판단 후보 | insight 상태와 SessionView | `CBT.jsx` 제안·설명·검토 화면, 후속 입력 시 승인 유효성 |
| confirmedResult | 사용자 CONFIRM | Spring 트랜잭션과 기존 영구 저장 엔티티 | 상세·보고서·PDF·임베딩·유사 사례 DTO; 승인 전 후보와 구별 |

Java 파일 경로: `mindot_back/src/main/java/com/my/mindot_back/records/service/`(서비스), `records/entity/`(엔티티). PDF는 `reports/service/`. 실제 전체 경로는 원장에 기록했다.

## 차단 사항과 구별할 관찰 사항

- **PDF의 긴 단일 문답 행**: `PdfExportService.java:595–652`는 행 높이가 페이지를 넘으면 한 번 새 페이지를 만든 뒤 모든 줄을 그린다. 단일 행 자체가 한 페이지보다 길면 잘릴 수 있다. 기존 표 렌더링의 제한이며 새 대화의 긴 메시지에서 노출될 여지가 있다. 이 문제만으로 CBT canary 진입을 막거나 AI 출력 길이를 임의 축소하지 않는다. 이후 PDF 장문 표본으로 확인하고 행 내부 페이지 분할을 검토한다.
- **Writer 형식 복구**: `mindot_ai/cbt_simple/graph.py:39`의 복구 입력은 원래 plan과 formatError이며 실패한 초안 자체를 전달하지 않는다. 엄밀한 의미 보존 형식 수정이 아니라 같은 목표의 재작성에 가깝다. 실제 실패 초안 보존이 필요하면 제공 가능한 초안만 전달하는 작은 개선을 검토한다. 별도 의미 검증 모델을 추가할 사유는 아니다.

## 읽은 범위와 검증 한계

원격 커밋의 tracked 파일 682개를 모두 목록화했다. 이 중 과거 평가 artifacts 340개와 캐시 메타데이터 2개를 현행 실행 소스와 구별했다. 나머지 340개에는 현행 소스·설정·테스트·문서, 바이너리 5개와 lockfile 1개가 포함된다.

Spring Java 156개, React JS/JSX/CSS 64개, AI Python 48개를 포함한 자사 기능 코드를 읽었다. 비활성 AI 엔진도 함수 본문·프롬프트를 읽고 활성 호출 여부를 구별했다. 일부 장문 비활성 코드는 AST 텍스트와 정확히 동일한 본문의 중복 매핑으로 검토했으며, 생략된 프롬프트 등은 원문으로 보완했다. 생성 wrapper·지역 데이터 사전·lockfile·바이너리는 역할과 구조/메타데이터를 확인했다. 이들을 기능 코드처럼 전 줄 의미 검토했다고 주장하지 않는다. 과거 raw/봉인 자료의 내용을 재채점하거나 복호화하지 않았다.

Codex 제출 보고서의 컴파일·빌드 성공은 제출자가 보고한 사실이다. 이번 리뷰에서 다시 실행하지 않았다. 런타임 DB 제약, 네트워크 장애, 동시 요청, 실제 모델 질문 품질 및 토큰 사용량은 이 정적 리뷰만으로 통과했다고 판단할 수 없다.

## 다음 단계

1. F1–F3를 수정하고 관련 소비자까지 자체 소스 검토한다. 현재 Agent 구조·정의·배점·평가 기대값을 문제와 무관하게 변경하지 않는다.
2. 새 커밋을 `fix/CBTAI`에 push하고 변경 이유·전체 파일 목록·미실행 상태를 제출한다.
3. ChatGPT가 새 SHA와 전체 기능 연결을 다시 확인한다. 이번 b7cc7cb 판정을 다음 커밋의 통과로 간주하지 않는다.
4. READY_FOR_TEST 이후: F1 최초 시작 장애 복구, F2 구형/신형 재시도와 복원, F3 유형 거부별 결과 소비, 기본 CBT 질문·도움·인식 변화·승인·종료, 중복·취소·메모리 유실을 작은 범위로 실행 검증한다. 그다음 제품 범위에 맞는 canary와 공식 평가로 진행한다. 구형 runner를 새 계약에 맞추지 않고 바로 호출하지 않는다.

기계 판정과 원장 hash는 동봉한 `review.json`에 기록했다. 이번 판정은 `CHANGES_REQUIRED`이며 테스트 시작 승인이 아니다.
