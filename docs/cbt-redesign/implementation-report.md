# CBT 단순화 후속 구현 보고

상태: `READY_FOR_GPT_FULL_BRANCH_REVIEW`. 이 문서는 테스트·canary·실모델 품질 통과 보고서가 아니다.

## 활성 구현

활성 경로는 `app.py → cbt_session_agent.py → cbt_simple`이다. 일반 대화는 Agent가 `ask_question(text)` 또는 `offer_help(text)`로 표시 문장까지 한 번에 결정한다. 완료만 Assessor와 같은 Agent의 최종 검토를 거친다.

활성 `cbt_simple`은 필요한 오류 타입, 진단, canonical JSON/hash, token 계산, strict JSON object/provider response parsing, 현재 deterministic safety detector를 자체 모듈에 둔다. `cbt_q11`과 `cbt_agent.py`는 역사 소스로 유지하지만 활성 import closure에는 포함하지 않는다. 새 의미 엔진이나 fallback 계층은 추가하지 않았다.

## 호출과 문맥

| 경로 | 생성 호출 | 정의 문맥 |
|---|---:|---|
| 질문·일반 도움 | SELECT 1회 | distortionDefinitions 없음 |
| proposal 설명 | SELECT 1회 | 기존 proposal 전체, distortionDefinitions 없음 |
| 완료 proposal | SELECT + ASSESSOR + ASSESSMENT_REVIEW, 최대 3회 | Assessor와 review에만 전체 정의 |
| RESTORE·성공 replay | 0회 | 저장 snapshot |

Writer prompt, Writer repair prompt, 출력 schema, wire phase, provider endpoint, 예약과 진단 이벤트는 현재 구조에서 제거됐다. function schema의 질문·도움 text 길이 제약도 제거하고 graph의 공백/500자 형식 검사만 유지한다.

## 상태와 실패

NEW/RESTORE/TURN, revision, request idempotency, failure retry, USER evidence, proposal 승인 전 의미는 변경하지 않았다. 활성 proposal의 `offer_help`는 외부 `EXPLAIN_PROPOSAL`로 매핑하고 같은 proposal을 보존한다. `ask_question`은 stale proposal을 보존하지 않는다.

관측 sink 예외는 Diagnostics 이벤트·카운터에 남지만 generation guard나 정상 commit을 실패시키지 않는다. guard는 runtime close와 task cancellation만 본다.

## 정적 검증 도구

`insight_static_check.py`는 현재 SELECT tool schema 전체와 Assessor/review schema, 세 phase serialization만 검사하도록 작성됐다. `insight_review_runner.py --suite ai`는 `test_insight_protocol.py`와 `test_question_proposal_fix.py`를 순서대로 실행하며 첫 실패를 그대로 반환한다.

이번 단계에서는 요청에 따라 static checker, runner, 테스트, 앱, canary, 유료 API와 공식 평가를 실행하지 않는다. 허용된 확인은 소스 읽기, AST 기반 문법 검사와 `git diff --check`뿐이다.

## 외부 영역

외부 Result outcome과 route/DTO는 그대로다. `mindot_front`, `mindot_back`, DB schema·데이터·제약조건을 수정하지 않는다. 최종 commit SHA와 원격 일치는 저장소 밖 작업 결과 보고에서 기록한다.
