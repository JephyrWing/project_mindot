# Mindot: 대화 구조 단순화와 유료 평가

이 지시문과 첨부 ZIP은 **이번 재설계를 처음 구현하기 위한 통합 지시**다. 사용자는 앞서 ChatGPT가 작성한 question-first-1 및 단순화 초안을 아직 Codex에 전달하지 않았다. 이전 지시를 받았거나 적용했다고 가정하지 마라. 이 지시문과 ZIP의 `simple-dialogue-1` 계약만으로 아래 작업을 시작하라.

시작점은 마지막으로 작업을 중단하고 보존한 REPAIR-13과 현재 실제 worktree다. 현재 파일과 차이를 먼저 보존한 뒤 새 계약을 바로 구현한다. question-first-1·slot compiler·이전 7개 prompt를 중간 단계로 구현할 필요는 없다. 기준 source·Q10·평가 원본의 위치는 첨부 asset-index에 있다.

목표는 “불필요한 검증을 최대한 제거하고 기능을 잃지 않는 선에서 구조를 단순화한다”이다. 가장 중요한 기능은 현재 대화에 맞는 CBT 질문 하나를 제공하고 사용자 답변·피드백에 따라 자연스럽게 진행하는 것이다.

## 작업 범위

구현, 자체 코드 리뷰, 필요한 오프라인 검증, 한 round canary, 조건 충족 시 Q10/Q11 공식 유료 평가368개 결과와 blind/full 제출까지 승인됐다. 중간에 승인·GPT 리뷰 대기를 새로 만들지 마라. 이 패키지가 검토된 설계라는 사실과 아직 작성하지 않은 제품 코드의 검토/테스트 상태를 구분하라.

제품 수정은 mindot_ai 안에서 한다. back/front는 읽기 전용이며 변경 필요는 별도 제안한다. 기존 사용자 작업과 중단 source/diff를 보존하고 reset/clean/restore하지 마라. 실제 LangGraph Agent·전체 원문 메모리·callable Writer/Assessor·기존 공개 URL/DTO·멱등성/취소/원자적 저장은 유지한다. 단순 SDK 호출과 서버 의미 라우터로 바꾸지 마라. git commit/push/배포/시스템 종료는 포함하지 않는다.

## 읽을 자료

README와 manifest를 확인하고 아래 docs만 현재 실행 명세로 읽어라. 기존 파일과 충돌하면 별도 작업 사본을 보존한다.

1. `docs/cbt-q11-simple/design-and-contract.md`
2. `docs/cbt-q11-simple/schema.py`, `prompts/`의 완성 문안5개
3. `docs/cbt-q11-simple/offline-checks.md`, `canary-plan.json`, `package-review.md`, `input-capacity-decision.md`
4. `docs/cbt-q11-evaluation/execution-contract.md`, `raw-export-contract.md`, `asset-index.json`
5. 같은 evaluation 폴더의 Q10/known/holdout 신원 manifest. key와 hidden 평문은 gate 전 읽지 않는다.

시작 전에 Drive iteration log `1SEXzKZPNTJzuzjrcjp8hiyBnMHU4oetu`를 읽기 전용으로 확인하라. 접근이 안 되면 `docs/cbt-q11-simple/iteration-log-snapshot.md`와 실제 최신성 한계를 기록하고 영향 없는 작업을 계속한다. 로그의 과거 지시보다 이번 명세가 우선한다. 작성자용 규칙과 grader용 rubric 전문을 구현·채점 지시로 읽지 마라.

## 반드시 바꿀 것

- 일반 SELECT의 전원 source review·네 영역 contribution/role 분류·slot/alias 중복 출력을 제거한다. Agent는 원문과 현재 대화 상태에서 다음 행동을 고르고, 실제 필요한 변화만 updates로 제안한다.
- 도구 schema는 고정형으로 둔다. source 개수에 따라 enum·필드가 증식하지 않게 하고 실제 ID·인용·현재성은 서버의 한 검증 경계에서 확인한다.
- 네 영역 coverage를 Assessor 호출과 완료의 선행조건에서 제거한다. 해당 분류를 생성하지 않는 턴은 raw export에 정직하게 null로 남긴다. 질문이 필요한지는 사용자의 의도와 현재 자료로 Agent가 판단한다.
- 최종 사용 근거는 Assessor에서만 추출한다. 왜곡 있음/뚜렷한 왜곡 없음/정당한 사실 확인/정직한 미판정을 구분한다. 미판정은 기존 CONTINUE 제어 안내이며 새 공개 status나 가짜 no-clear가 아니다.
- 사용자 정정·부분 철회/복권, 없음·생략·반복 항의, 예시/설명 요청과 실제 이행, 안전 사건과 보충 질문 사용 이력은 보존한다. 이를 위해 과거의 복잡한 분석 의무까지 재도입하지 마라.
- Writer는 message 하나만 작성한다. 공개 500자 한도·필수 구조를 유지하고 종결어미·물음표·줄바꿈·고정 예시 개수로 응답을 막는 검증을 제거한다. 같은 문체 검사를 Assessor gap/renderer에도 남기지 마라.
- 호출당 로컬 추정 입력48,000 tokens/직렬화196,608 bytes(192 KiB)를 정식 기본값으로 사용한다. 기존 큰 요청36,238에30% 여유를 둔 선택이며 provider 실제 usage와 구분한다. 정상 요청에 부족하면 첫 canary 전에 실제 SDK 입력을 측정해 모델 한도·승인 총량 안에서 조정하고 config/runner/canary의 effective 값을 함께 잠근다. 임시 허용을 다시 묻거나 과거12,000/96 KiB로 되돌리지 마라. 누적 예산/호출 수는 자동 증가하지 않으며 평가 중 설정을 바꾸지 않는다.
- 정상 질문2회/완료3회, 턴당 생성최대3·Moderation최대1을 유지한다. 별도 safety-recheck/argument-repair/coverage helper를 새 경로에서 실행하지 않는다. 실제 Writer 형식 오류에만 같은 plan으로 제한1회 복구한다.

완성 prompt는 제공 파일에서 그대로 적용한다. 실패한 사례를 본 뒤 문구·예문을 덧붙이지 마라. schema/입력/코드의 단순 버그는 명세 안에서 수정하되, 의미 계약을 바꿔야 해결되는 문제를 검증기 완화나 가짜 결과로 숨기지 마라.

## 완료 순서

1. 실제 시작 상태와 변경 범위를 기록하고 작은 새 core를 기존 facade에 연결한다. old core에서 필요한 저장·계약 helper만 재사용하고 두 실행 경로가 섞이지 않게 한다.
2. 변경한 기능 관계만 오프라인으로 검증한다. 옛 내부 구조를 강제하는 테스트는 이유를 남기고 교체하며, 기능 보존 테스트는 유지한다. 통과 뒤 새 검증 할당량을 만들지 않는다.
3. 실제 source/prompt/schema/runner/입력/기대값을 잠근다. canary는 실제 질문을 이어받는 두 연속 대화와 기본 기능 사례, 최대12 product request 한 round다. 독립 사례를 끝까지 모으고 매 실패마다 수정·재시작하지 마라.
4. 필수 기능이 통과하면 같은 source/runner로 formal lock, 기존 holdout 정식 공개/무결성 확인, Q10/Q11 184대응 사례=368결과 수집까지 계속한다. READY_FOR_HOLDOUT_LOCK에서 승인을 다시 묻지 않는다.
5. 공식 평가의 낮은 품질도 원시 결과로 보존한다. 기대값/분모/응답을 바꾸거나 좋은 답만 재생성하지 않는다. 완료 또는 실제 blocker에서 README·실행 Python 전체·raw·manifest를 담은 ZIP을 지정 Drive에 제출하고 확인한다.

불필요한 문체 취향은 평가를 막지 않는다. 잘못된 출처·사실 발명·사용자 내용 유실·반복 질문·도움 무시·명확한 현재 위험의 부적절한 처리는 기능 실패로 남긴다. 실제 접근 부재, 예산/기록/lock 장애와 required canary 실패는 정확히 보고한다. canary 통과까지 무제한 유료 반복은 승인되지 않았다.

최종 보고는 실제 상태, 질문 기능에서 확인된 변화, source hash, 오프라인/실제 모델 실행 수, canary 계획·실행·실패·미실행, 공식 result 수, usage와 제출 링크만 간결히 쓴다. 공식 품질 점수·우열·XLSX는 GPT가 blind-first로 처리한다.
