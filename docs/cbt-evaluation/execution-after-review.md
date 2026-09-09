# 테스트 실행 계약 · 지금은 비활성

이번 Codex 작업은 구현/정적 확인/push에서 끝난다. 이 문서는 이후 runner를 준비하기 위한 계약이며 지금 테스트 실행 명령이 아니다. 실행 전 ChatGPT의 실제 전체 브랜치 리뷰 결과 `READY_FOR_TEST`와 reviewedCommitSha를 확인한다. Codex가 템플릿을 채우거나 자체 점검으로 이 조건을 대신할 수 없다.

## 단계

1. ChatGPT 전체 소스 리뷰와 필요한 수정/push/review를 마친다.
2. 리뷰한 코드의 `offline-scenarios.md` 관련 기계 테스트를 실행한다. 실제 발견된 연결 위험에 필요한 검사만 추가한다. 실패하면 고친 코드와 영향 연결을 다시 리뷰하고 새 snapshot으로 진행한다.
3. 실제 요청을 직렬화해 대표 입력량·출력 여유·누적 예산을 고정한다. 같은 소스로 canary-plan의 12개 생성 요청을 한 round 수집한다. 처음 실패한 사례부터 반복 patch/retry하지 않는다. BLOCKED는 미실행이다.
4. 실제 질문·도움·부분 정정과 AFTER 제안/철회 경로의 사용자 결과를 확인한다. 기계 실패/무응답·의미상 핵심 기능 실패가 없으면 기존 조건부 유료 평가 승인 범위 안에서 공식 대응 수집으로 이어갈 수 있다. 문체 수준 관찰을 새 전체 중단 gate로 만들지 않는다. 현재 작업에서는 이 단계까지 자동 진행하지 않는다.
5. 공식 수집은 고정 Q10과 새 candidate에 같은 public170·새 기대·rubric을 적용한다. 결과와 비용 raw를 모두 보존한다. ChatGPT가 blind-first 채점 후 unblind/XLSX를 작성한다.

## 입력과 출력 경계

`known-inputs.jsonl`의 caseId는 runner 식별용이며 모델 prompt에 넣지 않는다. product context는 record + 실제 messages + 실제 저장된 historicalTypeReviews다. NEW는 첫 질문을 생성한다. TURN은 messagesBeforeRequest를 RESTORE한 뒤 newUserMessage를 한 번만 처리한다. user delta를 full snapshot에도 넣은 경우 inputRevision 처리 규칙으로 중복 방지한다. evaluator의 afterGate·expected·labelReference·rubricFamily는 제품 경로로 전달하지 않는다.

단일 public 입력은 제품 AI facade/실제 Agent+도구 경로로 실행한다. 10개 session은 실제 연속 대화와 restore를 사용한다. 독립 Assessor 함수만 호출한 결과를 완료 사례의 성공으로 대체하지 않는다. 전체 Spring/React 통합은 offline와 live canary의 별도 실제 연결로 확인한다.

Q10은 `reference/frozen-Q10/mindot_ai`와 q10-source-lock의 원본을 비교 기준으로 보존한다. 새 API가 필요하면 runner adapter가 같은 기록/문답/원래 검토 이력을 전달한다. Q10의 의미 prompt·판단을 새 AFTER 계약으로 고쳐 baseline처럼 부르지 않는다. 기존 원문에 없는 변화/라벨을 adapter가 추가하지 않는다. baseline의 실제 출력만 공통 형식으로 매핑한다. 새 lifecycle 기능의 지원 차이는 명시하고 baseline 엔진이 제공한 것처럼 발명하지 않는다.

공통 모델은 gpt-4o-mini이며 알려진 기존 온도 Agent/Assessor/검토0, Writer0.3을 시작값으로 한다. baseline의 고유 역할/출력/복구 정책과 candidate의 2/3회·SDKretry0 정책을 source와 함께 각각 기록한다. 공통 입력 예산은 actual effective 값으로 잠그되 원본 baseline 수정이 필요하면 허용된 수송/예산 adapter 차이로 명시한다. 비교 대상의 역할 수가 같다고 가정하지 않는다.

12,000 입력 cap은 복귀시키지 않는다. 새 candidate 기본은 추정48,000 tokens/196,608 UTF8 bytes. 상한 전체를 매번 비용으로 차감하지 않고 실제 요청+출력 여유를 예약해 usage로 정산한다. 추정 오차와 비용 미확인은 숨기지 않는다. canary 누적 사용+미확인 예약 토큰 상한은 기존 승인된 1,750,000, 최대36생성/12Moderation을 유지한다. 입력 예약의 기존 보정계수1.75는 추정 오차를 위한 운영값이며 실제usage와 구별한다. 이 총량이 모든 호출의 최대 input 조합을 보장하지는 않는다. 공식 평가의 누적 USD/token/call/time 예산은 **실제 source/runner와 측정 후 테스트 시작 문서에서 숫자로 잠근다**. 이미 받은 조건부 실행 권한을 반복 승인 질문으로 바꾸지 않는다. 현재 비활성 manifest의 null을 무제한으로 해석하지 않는다. 조건을 충족하지 않은 채 유료 호출하지 않는다.

whole-case 품질 retry는0이다. 전체 case 기술 retry는 이전 product 호출에서 응답을 하나도 받지 않은 사실이 확인된 transport/수집 실패에만 기존 최대1회를 적용한다. 불량 JSON/refusal/validation/의미 실패/수신 여부 불명은 retry 사유가 아니며 부분 성공 세션을 처음부터 재생성하지 않는다. 원시 첫 시도/실패를 보존한다. timeout의 비용이 미확정인 경우 예약을 유지한다. 공식 실행 중 source/prompt/expected 변경은 새 실행으로 분리한다.

## 봉인과 분모

public170는 160 single+10session, 두 버전340슬롯이다. 기존 봉인14(12single+2session)는 원래 cipher/manifest를 보존해28슬롯을 별도로 계획한다. 구현/push/리뷰 단계에서는 키나 평문을 읽지 않는다. 검토와 평가 잠금·기존 공개 조건 충족 뒤 공개한다. 새 정의와 양립하지 않는 옛 expected가 있으면 v1.13의 TEST_INVALID/한계 규칙을 적용하고 원래 기대를 고치지 않는다. 368개 슬롯을 기록하더라도 368개 모두 유효 비교라고 선결론 내리지 않는다. 전체 수량은 사전 계획, 실행, blocked/invalid/실패, 유효 채점 분모를 구별한다.

blind/full의 표준 키·제안·무응답 표현은 v1.13을 따른다. 내부 장부·버전명·trace·usage는 full에만 저장한다. full을 먼저 열고 독립 blind 채점이라고 보고하지 않는다. 실패 raw·source 누락을 보완하기 위해 모델을 재호출하지 않는다.
