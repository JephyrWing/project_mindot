# 최신 설계 결정 발췌 — 읽기 전용 snapshot

원본 Drive ID: 1SEXzKZPNTJzuzjrcjp8hiyBnMHU4oetu
원본 업데이트 직전 확인 시각: 2026-09-08T18:39:41.508Z
원본 업데이트 직전 SHA-256: ee35764b10a5290682176be3a94c20a16a0936bfc24377116ee62e969ea294f4
아래는 이번에 추가한 최신 결정 발췌이며 전체 과거 로그가 아니다. 실행 Codex는 가능하면 원본 최신 로그를 먼저 읽고, 접근 불가 시 이 발췌의 최신성 한계를 표시한다. 과거 구현/점수를 실행 지시로 재사용하지 않는다.


---

## 2026-09-09 UTC — simple-dialogue-1 구조 단순화 설계·프롬프트 확정, 제품 미구현·미실행

사용자는 거시 검토에서 지적한 방향에 동의하고 불필요한 검증을 최대한 제거하되 기능을 보존하도록 승인했다. 이전 question-first-1은 per-source 분석 의무·네 영역 완료조건·문체 검증을 과도하게 유지했으므로 본 계약으로 교체한다. 기존 구현·오프라인·한 round canary·조건부 공식 유료 평가와 raw 제출 승인은 유지한다.

확정한 변경:

- 일반 SELECT의 필수 source review/coverage/contribution/role·동적 slot/alias schema를 제거한다. 원문과 현재 대화에서 행동을 선택하고 필요한 상태 변화만 sparse event로 제안한다.
- 네 영역 분류를 평가 준비도에서 분리한다. 최종 근거는 Assessor가 현재 유효 원문에서 추출한다. DISTORTION_PRESENT/NO_CLEAR_DISTORTION/정당한 FACT_BOUNDARY_REQUIRED/정직한 내부 UNRESOLVED 후보를 구분한다.
- 공개 API에는 새 상태를 추가하지 않는다. 미판정과 사용자 중단은 CONTINUE 제어 안내이며 실제 DB 완료/취소나 no-clear를 발명하지 않는다. nullable evidence/acknowledgement와 기존 사용자 확인 초안을 보존한다.
- 실제 LangGraph Agent·전체 원문/checkpoint·callable Writer/Assessor·같은 Agent의 최종 판단 권한을 유지한다. 일반2/평가3/턴최대3, Moderation1. 새 경로에서 safety-recheck/argument-repair를 제거하고 필요한 Writer 형식 복구만 공유 한도 안에서 허용한다.
- 공개500자/필수 구조를 유지하고 요?/까?·ASCII물음표·줄바꿈·고정예시수 등 임의 문체 gate와 동일 인자의 반복 검증을 제거한다.
- 정정·부분 철회/복권, 없음/생략/반복항의, 요청 대상/취소/실제 이행, 중단/재개·안전사건·gap 예산, 원자적 commit/멱등성/취소/실제 raw는 보존한다.
- canary는 두 실제 3턴 대화와 여섯 독립 기능 사례=12요청/최대36생성/Moderation12. 후속은 실제 질문에 연결한 고정 자발 발화이며 별도의 의미 적합성 사전 차단을 제거했다. 과거 결과에 맞춘 live patch/retry는 하지 않는다.
- rubric v1.11은 네 영역 강제 제거와 null 관찰, SUMMARY_ONLY/USER_STOP 구분, 예시 고정 개수 제거를 반영한 실질 해석 변경이다. 기존 배점/산식/안전 다섯 항목을 유지하고 새 비교 양쪽에 동일 적용한다. known/hidden 원문·기대값·분모는 그대로 두고 legacy 충돌을 별도 해석한다. 기존 점수에는 소급하지 않는다.
- 작성 기준 v1.19도 같은 원칙과 우선순위를 반영했다. 작성/저장 규칙 자체는 Drive에 올리지 않는다.

설계 근거는 REPAIR-13의 공개 DTO/completion/policy/memory/rendering와 이전 패키지, 첨부 워크시트다. 보조 검토에서 공개 제어 매핑·rubric 충돌·연속 답변 사전 gate·phase 기대표를 대조하고 수정했다. Spring/React 실제 소비 코드는 이 작성 환경에서 미확인, 실행 Codex의 읽기 전용 확인 대상으로 명시했다.

artifact 확인: 고정 schema·대표 JSON 인자·문안 길이·12노드 연결·공식 원본 신원 보존·ZIP manifest를 확인한다. 이것은 제품 테스트/실제 모델의 의미 수행 성공이 아니다. SELECT schema는 로컬 추정3,998토큰/15,779bytes이며 실제 전송/기존 요청 동일조건 비교가 아니다. 정확도·비용 절감률·canary 통과를 보장하지 않는다.

문안 codepoint: Agent 2054, Writer 773, Assessor 1499, 최종검토 415, Writer repair 266.
설계 계약 SHA-256: a5519331f69a6fbe80aecca11a65db1035195524186b24e07a530c293ea534e5
schema SHA-256: 12a72dcd194ca6929ca21cab63bccee02694947ab817d9dd0d3db95bf12afb88
rubric v1.11 SHA-256: 2bdfd89a6ecf7bc7c74aa0b21f47bc3a8e7f2288467290868d40af91b3fb620f
rubric Drive: https://drive.google.com/file/d/1brCwhEPqkSAaUSsjyuslvj87muW-JjE8/view?usp=drivesdk

전달본 고정 이름: Mindot-Q11-Question-First-Codex-Prompt.md, Mindot-Q11-Question-First-Package-20260908.zip. 파일 이름의 이전 날짜와 별개로 내용은 simple-dialogue-1/2026-09-09 갱신본이다. 이전 이력은 보존한다.

현재 상태: DESIGN_AND_PROMPT_PACKAGE_COMPLETE; PRODUCT_IMPLEMENTATION_AND_EVALUATION_PENDING. 이 환경에서 새 제품 코드·제품 오프라인·canary·유료 평가를 실행하지 않았다. 새 candidate source hash는 아직 없으며 과거 공식0/368과 DEVELOPMENT_BLOCKED 결과를 통과로 바꾸지 않는다.




---

## 2026-09-09 UTC — 작성 규칙v1.20·입력 용량 정식화, 제품 미실행

사용자는 작성 규칙과 입력 상한 반영을 요청한 뒤, 저장 전에48,000의 적정성부터 판단하고 적정값으로 조정하라고 지시했다. 직전 단순화 원칙은 이미v1.19에 반영됐으나 최종 안내에서 빠졌으며, 전체 입력 용량의 지속 적용 기본값은 없었다.

- 적정성 검토: 기존 UNIT 최대36,238×1.3=47,109.4, bytes139,404×1.3=181,225.2로48k/192 KiB가 적절한 현재 기본값이다. 새 전체 runtime 입력은 미구현·미측정이라32k로 낮추는 근거도,48k 초과 증거 없이64k로 높이는 근거도 부족하다. REPAIR-13 실제 SELECT local16,583/provider26,149를 구분하고1.75 예약 보정+출력8,192=92,192와 공식128k context를 대조했다. input-capacity-decision.md에 근거·한계를 기록했다.
- 작성 규칙v1.20에 입력48,000 tokens/196,608 bytes(192 KiB)를 정식 기본값으로 명시했다. REPAIR-13 llm.py의 실제 적용값과 현재 실행 패키지 값은 같았다. 정확한 과거 승인 문구를 추정하지 않고 현재 사용자의 반영 지시로 정식화했다.
- system prompt 글자 수, 생성 호출당 입력, phase별 출력, 전체 canary/공식 누적 예산을 분리했다. 과거12,000 tokens/96 KiB를 새 실행 기본값으로 복구하지 않는다.
- 정상 대표 요청에 부족하면 첫 canary 전 SDK 입력을 측정해 보통20~30% 여유를 둔 설정으로 조정한다. 확인된 모델 context/output·전송 한도와 승인 총량/호출 수 안에서 같은 임시 허용을 반복 요청하지 않는다. config·runner·canary의 effective 값과 근거를 함께 잠근다.
- canary 시작 뒤 같은 round 및 공식/holdout 공개 뒤 설정을 조용히 바꾸지 않는다. 추가 유료 round·모델 교체·기대값 변경·출력/누적 예산 증액 허가는 아니다.
- 누적 canary1,750,000과12요청/36생성/12Moderation은 유지했다. 이는 모든 요청 최대 조합의 실행 보장이 아니다. 실제 요청별 추정+출력으로 예약/정산하고 잔액 부족을 BUDGET_BLOCKED로 구분한다.
- 불필요한 모든 exact 검증 이관·최대 source/review/contribution 조합 검사 문구도 현재 기능 경계로 정리했다. 설계/실행/오프라인/메인 프롬프트/canary 설정/README와 패키지 기록을 동기화했다.

평가 rubric v1.11·배점/산식·제품 prompt5개·schema·known/hidden 원본과 기대값은 변경하지 않았다. 원본 역사 문서의 옛 숫자는 보존한다. 새 제품 코드·제품 테스트·canary·유료 API 실행은 하지 않았으며 공식 수집0/368 상태를 변경하지 않는다.
현재 패키지 의미 계약 simple-dialogue-1, 용량 정책 revision capacity-policy-1. 작성 기준은 같은 파일 ID로 갱신하며 Drive에는 올리지 않는다. 기존 iteration log에 이 항목을 추가한다.


---

## 2026-09-09 UTC — Codex 최초 전달 상태 명확화, 제품 미실행

사용자가 앞서 작성한 재설계 지시를 아직 Codex에 보내지 않았다고 명시했다. 기존 통합 ZIP은 필요한5개 완성 prompt·schema·설계·평가 지시와 원본 위치를 이미 포함했지만, main/README/design의 ‘이전 지시 교체·이전 전달본 갱신’ 표현이 수신·구현을 전제로 읽힐 수 있었다. 해당 표현을 정정했다.

이번 지시문과 ZIP을 처음 받은 Codex가 마지막 중단 REPAIR-13 및 보존된 현재 worktree에서 simple-dialogue-1을 직접 구현한다. question-first-1이나 이전7개 prompt를 먼저 적용하는 중간 단계는 없다. asset-index에 최초 전달 상태를 명시하고 holdout key를 이미 전달받았다고 읽히는 표현도 제거했다. 기존 승인 범위는 이 통합 지시에 완전하게 적혀 있다.

변경은 전달 상태·시작점의 설명이다. 모델 prompt5개·schema·canary 사례/설정·48,000 추정 입력/192 KiB·평가 수량/채점 기준·원본 잠금은 변경하지 않았다. 작성 규칙v1.20은 유지한다. 실제 제품 코드·오프라인 제품 테스트·canary·유료 API 실행은 없다. 사용자가 실제 전송했다고 알리기 전까지 새 지시를 전달/적용 완료로 기록하지 않는다.
