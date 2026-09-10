# 구현부터 공식 유료 평가 제출까지

실행 계약 `simple-dialogue-1`, 2026-09-09. 이번 통합 지시는 구현·자체 코드 리뷰·필요 오프라인·단일 canary·통과 시 공식 대응 유료 평가·Drive 제출까지 승인한다. 앞선 미전달 초안의 수신·적용이나 구현 완료를 전제로 하지 않는다. 마지막 중단 코드에서 시작해 아래 순서를 중간 재승인 없이 수행한다. 이 패키지는 설계 명세이며 아직 만들어지지 않은 제품 코드가 GPT 검토를 통과했다고 쓰지 않는다.

## 1. 실행 자료와 범위

`asset-index.json`의 실제 source/input 위치를 사용한다. 중단된 REPAIR-13은 출발점이지 통과 버전이 아니다. 실행 중 사용자 변경을 보존하고 mindot_ai만 수정한다. back/front는 읽기 전용이다. Q10 baseline, 기존 known170 입력과 기대값, sealed hidden14는 변경하지 않는다. rubric v1.11은 byte/hash 잠금만 확인하고 구현자가 채점 전문을 읽어 튜닝하거나 점수를 매기지 않는다.

현재 `docs/cbt-q11-simple/`의 명세·schema·5개 prompt와 이 실행 계약만 active다. 과거 known/source/holdout lock은 입력 신원 확인용이며 과거 candidate·budget·review 대기를 현재 실행 지시로 승계하지 않는다. old canary·question-first compiler·7개 prompt는 실행하지 않는다.

환경은 프로젝트 내부 격리 dependency를 사용한다. 운영 dotenv·전역 설정·동료 프로젝트를 수정하지 않는다. 실제 Python/설치 version/import 경로와 source diff를 기록한다. 시작 시 최신 iteration log `1SEXzKZPNTJzuzjrcjp8hiyBnMHU4oetu`를 읽기 전용으로 확인한다. 접근 불가 시 제공한 snapshot과 원본 metadata로 계속할 수 있는 범위를 설명한다. 로그 갱신은 GPT 담당이다.

## 2. 오프라인과 source 잠금

`offline-checks.md`의 기능 관계와 실제 변경 영향만 검증한다. dummy credential·SDK resource fake·테스트 프로세스의 network-fail guard를 사용한다. 내부 정답을 직접 끼워 넣은 함수 검사를 통합/실제 모델 성공으로 쓰지 않는다.

제품 자체 리뷰 후 공개 request→실제 graph→SDK resource fake→실제 tool→공개 DTO→commit 연결을 확인한다. 공개 OpenAPI와 Python client 소비 관계를 확인한다. 동료 코드의 실제 비호환이 없고 기능 gate가 통과하면 추가 테스트 조합/개수 목표를 만들지 말고 canary로 간다. 옛 구현 내부 형식을 강제하는 테스트는 새 기능 계약으로 교체하고 이유를 기록한다. 실제 기능 실패 테스트를 무시하는 것과 구분한다.

첫 canary 전 source 전체 bytes/hash, 실제 prompts/schema, runner/adapter/export normalization, canary 입력/기대값, 모델/temperature/예산을 한 lock으로 고정한다. 중복 manifest를 여러 번 재검증하는 별도 승인 gate는 만들지 않는다. 최초 검증과 실행 직전 변경 없음 확인이면 충분하다.

## 3. canary 한 round

`docs/cbt-q11-simple/canary-plan.json`의 12 계획 product request를 실행한다. 두 실제 연속 대화가 중심이다. 각 후속은 실제 앞선 질문에 고정 답변을 연결하며, 기대 질문·coverage·정답 state를 만들어 주입하지 않는다. 고정 후속 발화는 사용자가 자발적으로 추가한 맥락·정정·진행 의사다. 앞선 질문을 정확히 답하지 않았다는 이유로 발송 전 의미 검사를 추가하지 않는다. 임의 질문/답변 교체는 하지 않는다. 독립 root는 한 사례 실패 때문에 중단하지 않는다. 종속 노드는 실패 부모가 없으면 BLOCKED_BY_PARENT이지 PASS가 아니다.

한 round 최대 생성36 / Moderation12 / 생성 token 실제 관측+미확인 예약 합계1,750,000. 요청당 일반2/최대3 생성, Moderation1, retry0. phase별 출력 cap SELECT8192, ASSESSOR1800, WRITER650, ASSESSMENT_REVIEW1200, WRITER_REPAIR650. 일반 SELECT에 8192를 매번 채우라는 뜻이 아니다. 이를 줄이는 최적화도 평가 중 변경하지 않는다.

입력 추정48,000 tokens/실제 SDK 입력196,608 bytes(192 KiB)를 정식 기본값으로 사용한다. 첫 canary 전에 아래 절차로 조정한 경우에는 함께 잠근 effective 설정을 사용한다. 실제 messages/tools/response_format 직렬화 크기와 tokenizer 또는 추정법을 기록한다. dispatch 전 필수 후속 출력·호출권과 총 예산을 예약하고 usage 수신 뒤 정산한다. 과거 실측 차이를 고려해 입력 예약은 추정×1.75+output cap을 쓰되 provider의 보장된 상한이라고 하지 않는다. usage 미확인은 0이 아니라 예약 유지다. 예산 소진으로 미실행한 사례는 통과가 아니다.

모델 gpt-4o-mini, Agent/Assessor temperature0, Writer0.3. provider timeout30초/product request180초/checkpoint TTL600초는 기존값 유지. 추가 summarizer/semantic grader/helper LLM 없음. 실제 장기 session deadline은 maxTurns×product deadline+cleanup margin으로 잠근다.

canary 판단은 사전 정의된 사용자 기능만으로 한다. 맥락 관련 질문, 이미 답한 내용/없음/반복 항의 반영, 실질 답변+요청 보존, 정직한 평가/중단, 명확한 현재 위험 기본 대응, 실제 응답 제공과 호출 무결성이다. 특정 내부 phase·coverage·예시 접두어·문장부호를 정답으로 강제하지 않는다. 제품에서 거부된 응답을 단순 문체 관찰로 쓰지 않는다.

모든 applicable 필수 기능과 실행 무결성이 통과하면 CANARY_ELIGIBLE이며 공식 평가로 계속한다. 문체 취향·기능을 바꾸지 않는 어색함은 관찰 사항이다. 첫 실패마다 patch/restart하지 않는다. blocker가 남으면 독립 결과를 끝까지 확보해 CANARY_BLOCKED와 최초 원인·미실행 후속을 제출한다. 이 승인은 통과할 때까지 유료 round를 반복하는 허가는 아니다.

### 입력 용량 설정과 사전 조정

- 수치 선택 근거는 `docs/cbt-q11-simple/input-capacity-decision.md`다. 기존 최대36,238×1.3=47,109.4이므로48k를 유지한다. 새 제품 전체 wire의 실측 완료를 의미하지 않는다.
- 48,000 tokens/196,608 bytes는 임시 예외가 아니며 12,000/96 KiB로 되돌리지 않는다. 모델의 실제 context 한도·system prompt 문자 수·누적 평가 예산과 구분한다.
- START, 일반 턴, 최종 평가, 긴 이력/정정의 정상 대표 요청에서 실제 SDK input을 측정한다. 기존 의미 있는 fixture와 통합검증 payload를 재사용한다. 기본값이 충분하면 추가 용량 시험이나 더 작은 cap 최적화를 만들지 않고 진행한다. 최대 source/review 조합 생성기나 추가 유료 capacity 탐색은 만들지 않는다.
- 기본값이 부족하거나 대표 최대값에 여유가 부족하면, 첫 canary 전 측정값에 보통20~30% 여유를 두어 입력과 bytes를 각각 조정할 수 있다. 확인된 모델 context 안에서 출력/framing 여유와 이미 승인된 총량·호출 수를 지킨다. 같은 허용을 다시 묻지 않으며 제조사/전송/접근 한도를 우회하지 않는다.
- 기존 context 경계는 `ceil(inputEstimate×1.75)+phaseMaxOutput`을 확인된128,000 context와 대조한다. 기본48k/최대출력8,192에서92,192다. 추정 보정은 보장된 provider 상한이 아니며 actual usage를 별도 기록한다. 별도 validator/LLM을 추가하지 않는다.
- 변경 전후 값·대표 측정·추정법·모델 한도 확인 출처를 저장한다. 제품 config, runner, canary JSON의 effective 입력/bytes 값을 같은 값으로 고정한다. JSON의 현재 두 수치는 시작값이며 사례 원문·기대 의미를 바꾸지 않는다. source/runner/config hash를 첫 canary lock에 포함한다.
- canary 시작 뒤 같은 round에서 조정·재시도하지 않는다. 공식 source lock 이후, 특히 holdout 공개 뒤 설정을 바꾼 결과를 같은 비교로 합치지 않는다. 후속 설정 변경은 새 lock과 해당 실행 권한을 따른다.
- canary 누적1,750,000 tokens와 최대36생성/12Moderation, 기존 공식 평가 권한은 그대로다. 입력 상한을 올렸다고 총량이나 호출 수가 늘어난 것이 아니다. 총량은 실제 요청별 추정/예약으로 집행하고 모든 호출에 48,000을 일괄 차감하지 않는다.
- 이 누적 총량이 모든 호출의 최대 허용 크기를 감당한다고 보장하지 않는다. 기본값에서 36×48,000×1.75 + 12×11,192 = 3,158,304는 모든 호출이 최대 입력이고 각 요청이 최대 출력 예약 경로를 쓰는 보수적 조합이며 예상 사용량이 아니다. 그 조합의 비용을 선지급/소비할 이유는 없다. 실제 잔액 부족은 BUDGET_BLOCKED로 보고하고 총량을 몰래 확대하거나 제품 입력 용량 오류로 기록하지 않는다.

## 4. 공식 비교와 holdout

Q10/Q11에 같은 known170+hidden14=184 논리 사례, 버전 결과368개를 수집한다. 368은 API 호출 수가 아니다. known single160+long10, known 최대220 product request/version. hidden 공개 전 version별 ceiling은 `known220 + hiddenSingle12 + sum(hiddenLong.maxTurns)` 수식으로 잠근다. 원래 Q10 모델 호출/retry 정책을 확인해 그대로 기록하고 Q11 최대3을 Q10에 덮어씌우지 않는다.

공식 readiness에는 같은 candidate/runner와 Q10 source, known bytes, holdout manifest/ciphertext, rubric v1.11 bytes/hash, 공개 OpenAPI, 익명 mapping, 실행 순서·실제 baseline/candidate 비용 추정·예약 예산을 포함한다. source/input/runner/rubric lock은 하나의 연결된 실행 manifest로 관리한다. 예산을 계산·집행하되 이미 승인된 범위 내 실행을 재승인 질문으로 바꾸지 않는다.

seed20260908, case별 version 균형 교차, 전체 동시 product request 최대2(버전당1). 모델 seed는 양쪽 지원 시 공통 적용하고 아니면 미지원 기록. known long 입력은 기존 maxTurns/step 내용을 유지한다. 숨겨진 사례를 공개한 뒤 더 유리한 schedule·답변·정규화를 고르지 않는다.

READY_FOR_HOLDOUT_LOCK 영수증 저장 뒤 asset-index의 기존 key에 정식 접근해 복호화·검증하고 계속한다. 접근권한 부족은 실제 blocker로 보고하며 우회하지 않는다. 공개 전에는 key/평문을 열지 않는다. AES-256-CBC/PBKDF2-HMACSHA256/250000 설정, ciphertext/tar/모든 내부 file hash·14case·안전 상대경로를 확인한다. key는 file/stdin으로만 사용하고 argv/log/ZIP/제품 prompt에 넣지 않는다. 봉인 이력과 과거의 독립성은 소급 재작성하지 않는다.

공식 첫 출력 뒤 source/prompt/input/expected/rubric/adapter/runner/mapping을 바꾸면 EVALUATION_INVALID다. 정상 응답의 낮은 품질이나 case 오류는 그대로 기록하면서 나머지를 수집한다. 오래된 기대값과 새 대화 기준의 충돌도 해당 결과로 남기고 기대값/분모를 바꾸지 않는다. 전체 lock 변동·기록 불능·통제되지 않는 dispatch는 중단 사유다.

기술 whole-case retry는 이전 모든 product 호출에서 응답을 하나도 받지 않은 것이 확인된 transport/수집 실패에만 최대1회다. 불량 JSON/refusal/validation/의미 실패/수신 여부 불명은 retry 사유가 아니다. 부분 성공 long session을 처음부터 다시 만들지 않는다. 재시작 시 durable journal의 완료 case를 건너뛰고 실제 commit에서 정확히 복원 가능한 다음 미실행 step만 진행한다. 수집된 결과를 좋은 답변으로 대체하지 않는다.

## 5. 제출

성공 상태는 RAW_COLLECTION_COMPLETE_AWAITING_BLIND_GRADING. blind/full ZIP을 분리하고 둘 다 README·manifest를 포함한다. full에는 실행한 mindot_ai 관련 Python 전체, tests/requirements/설정·runner/adapter, source diff·lock, 모든 phase 실제 request/response·usage·callID·validation·commit·시간과 공식 journal을 넣는다. 제품 소스를 diff만 전달하지 않는다. credential·key·cache·가상환경·무관한 repository는 제외한다.

`raw-export-contract.md`대로 양쪽 동일 중립 schema와 결측값 규칙을 쓴다. per-turn coverage를 만들지 않는 버전에 가짜 coverage를 채우거나 추출용 LLM을 돌리지 않는다. 원래 응답·도구 args를 성공한 것처럼 고치지 않는다. JSON 구조·manifest 보완을 위해 API를 다시 실행하지 않는다.

- 성공: Mindot-Anonymous-Evaluation-Blind-<UTC>.zip, Mindot-Q10-Q11-Evaluation-Full-<UTC>.zip.
- 중단: Mindot-Q11-Simple-Dialogue-Audit-<UTC>.zip. 실제 완료/실패/미실행 수를 명시한다.
- Drive 결과 폴더 `1kQ8MriKCP1afqw7jpLFlHzaKAPuDGmgW`의 실제 이름/parent를 확인하고 직접 업로드한다. 기존 실행 ZIP을 덮지 않고 remote ID/name/parent/bytes/hash 또는 재다운로드 hash를 확인한다. 추가 공유 권한을 만들지 않는다. 업로드 결과 불명 시 원격 상태부터 확인한다.

Codex는 점수·우열·XLSX를 만들지 않는다. GPT가 blind만 먼저 채점해 grade를 잠근 뒤 full을 매칭한다. 제출에 실패하면 확보된 파일 위치와 실제 실패를 남기고 완료했다고 하지 않는다. git commit/push/배포/PC 종료는 이번 작업에 포함하지 않는다.
