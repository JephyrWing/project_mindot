# Mindot CBT 현재 계약

이 문서는 `mindot_ai/cbt_session_agent/model-contracts.json`과 활성 runtime을 설명한다. JSON 파일이 모델 도구·후보 schema의 canonical source다.

## 활성 경로

`app.py → cbt_session_agent package → cbt_session_agent.py facade → service.py → graph.py → provider.py/wire.py`

활성 패키지는 과거 `cbt_agent.py`, `cbt_q5`, `cbt_q11`과 분리되어 있으며 해당 legacy 소스는 삭제됐다. 평가용 `mindot_ai/artifacts`의 동결 사본만 역사 자료로 유지한다.

## Agent 도구

| 도구 | 인자 | 의미 |
|---|---|---|
| `ask_question` | `text` | 사용자가 지금 답할 수 있는 맥락상 CBT 질문 하나를 바로 표시한다. 활성 제안 뒤 실제 정정·철회·새 탐색이면 기존 제안을 제거하고 `DIALOGUE`로 돌아간다. |
| `check_current_thought` | `text` | 최초 판단을 재고했을 합리적인 신호 뒤, 그 판단을 지금 어떻게 보는지 한 번 확인한다. 외부에는 기존 `QUESTION`으로 표시되고 내부 질문 목적만 다음 TURN에 연결한다. |
| `offer_help` | `text` | 직전 질문 또는 현재 제안을 쉬운 말이나 중립적인 가상 예시로 설명한다. 활성 제안이 있으면 동일 제안과 `PROPOSAL_REVIEW`를 보존한다. |
| `assess_completion` | `fallbackQuestion` | 생각 변화 가능성을 Assessor가 독립 판단하도록 요청한다. 미성립 또는 최종 검토 거부 시 같은 호출에서 작성한 질문으로 대화에 복귀한다. |
| `respond_control` | 없음 | 질문 중단과 기존 나중에 이어하기·완전 종료 선택을 안내한다. |
| `respond_safety` | `action`, `reason` | 명확한 현재 위험의 중단 또는 필요한 최소 안전 확인을 처리한다. |

세 표시 도구의 `text`에는 provider function schema의 길이 제약을 중복하지 않는다. `graph.py`가 공백 표시 문장과 500자 초과만 형식 검사한다. 자연어 의미 regex, keyword/score gate, 금지 문구 validator, repair LLM, 자동 생성 재시도는 없다.

## 생성 경로와 문맥

- 일반 질문·도움·현재 생각 확인: `SELECT` 1회. Agent가 의미와 표시 문장을 함께 작성한다.
- 현재 생각 확인 다음 답변이 NOT_ESTABLISHED: `SELECT → ASSESSOR`, 최대 2회. 확인 질문을 반복하지 않고 일반 CBT 지원 또는 질문으로 돌아간다.
- 완료: `SELECT → ASSESSOR → 같은 Agent의 ASSESSMENT_REVIEW`, 최대 3회.
- `distortionDefinitions`: `ASSESSOR`와 `ASSESSMENT_REVIEW`에만 전체 정의를 제공한다. `SELECT`에는 제공하지 않는다.
- 입력 추정 상한 48,000 tokens, 요청 196,608 bytes, 출력 cap은 SELECT 8,192 / ASSESSOR 1,800 / ASSESSMENT_REVIEW 1,200이다.
- SDK 자동 retry는 0이다. RESTORE와 성공 결과 재전달은 생성하지 않는다.

## BEFORE, AFTER와 proposal

BEFORE는 최초 `record.automaticThought`다. 사용자가 최초 기록 자체를 명확히 정정한 경우에만 `beforeCorrection`이 이를 대체하며 실제 USER 인용을 요구한다.

AFTER는 사용자가 처음 판단의 잘못을 알아차리고 그 판단에 따라 생각을 실제로 수정한 의미가 대화에 드러난 뒤 Assessor가 정리한 문장이다. 단순 맞장구, 질문 이해, 새 생각 언급, 도움 요청, 변화 없음, 기록 오기 정정만으로는 AFTER가 아니다. `afterEvidence`는 실제 USER 메시지 번호와 원문 구절만 가리킨다.

현재 생각 확인 바로 다음 USER 답변은 Agent의 추가 명확성 판정 없이 Assessor로 전달된다. Assessor가 AFTER의 ESTABLISHED/NOT_ESTABLISHED를 유일하게 판정한다. 같은 Agent의 최종 검토는 그 판정을 다시 하거나 뒤집지 않고, ESTABLISHED 후보의 원문 충실도·근거·왜곡 적합성만 확인한다. proposal은 사용자 승인 전 저장·확정 결과가 아니며 자연어 동의도 승인으로 간주하지 않는다.

활성 proposal 설명은 내부 `offer_help`를 사용하며 외부 소비자 호환을 위해 outcome을 `EXPLAIN_PROPOSAL`로 매핑한다. proposal ID·내용·`PROPOSAL_REVIEW`는 그대로 유지된다. 실제 정정·변화 철회·새 탐색은 `ask_question`으로 기존 proposal을 제거하거나, 새 completion 판단으로 교체한다.

## 세션과 외부 계약

Spring은 start/resume에서 전체 이력과 phase·proposal·pending job을 보내고, 메모리 유지 중 TURN에는 새 USER 메시지 하나를 보낸다. AI runtime은 질문과 답변을 messageNumber 순서로 누적한다.

- NEW: 빈 이력과 pending job으로 첫 생성.
- RESTORE: provider를 만들지 않고 전체 snapshot을 그대로 복원.
- TURN: `baseRevision → inputRevision` delta 하나를 검증해 append.
- 라이브 runtime: 현재 생각 확인 목적을 내부 메모리에만 보관해 바로 다음 TURN의 routing에 제공.
- RESTORE: 내부 목적 마커는 복원하지 않고 전체 시간순 ASSISTANT/USER 대화로 직전 확인 질문과 답변의 결속을 판별.
- 동일 request ID와 같은 입력의 성공 결과는 재생성하지 않는다.
- 같은 request ID의 다른 입력은 `REQUEST_CONFLICT`다.
- 메모리·revision 불일치는 모델 호출 전 `RESYNC_REQUIRED`다.

외부 route, DTO, Result outcome enum과 Spring 승인 payload는 변경하지 않는다. `QUESTION`, `HELP`, `EXPLAIN_PROPOSAL`, `PROPOSAL`, `CONTROL`, `SAFETY_CLARIFY`, `SAFETY_STOP`, `UNRESOLVED` 의미를 유지한다.

## 실패와 관측

입력 snapshot은 생성 전에 보존되고, 성공한 결과만 메시지·revision·phase를 commit한다. 실패한 동일 attempt는 기술 실패로 재전달되며 새 attempt에서 같은 delta를 중복 append하지 않는다.

Diagnostics sink 예외는 `audit_sink_failure` 카운터에 남지만 제품 generation guard나 상태 commit을 차단하지 않는다. guard는 runtime close와 task cancellation만 검사한다.
