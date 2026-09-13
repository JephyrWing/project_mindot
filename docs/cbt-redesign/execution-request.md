# 다음 단계: Q14 수정본 GPT 전체 소스 재리뷰 요청

원격 `fix/CBTAI`의 최신 SHA를 고정한 뒤 전체 소스를 읽어 Q14 현재 생각 확인 흐름과 주변 기능 영향을 다시 검토한다. 이 문서는 구현 재실행이나 테스트 실행 지시가 아니며 Writer 기반 과거 설계를 복원하지 않는다.

## 현재 Q14 기준

- 일반 질문·도움·현재 생각 확인은 Agent SELECT 한 번이다.
- Agent 도구는 `ask_question(text)`, `check_current_thought({})`, `offer_help(text)`, `assess_completion(fallbackQuestion)`, `respond_control()`, `respond_safety(action, reason)`다.
- 사용자가 BEFORE를 재고했을 합리적인 신호를 보이면 Agent는 `check_current_thought({})`로 확인 시점만 선택한다. 서버가 `그렇다면, 처음에 떠올랐던 판단을 지금은 어떻게 보고 있나요?`를 기존 외부 `QUESTION`으로 고정 표시한다.
- 그 질문에 대한 실제 USER 답변은 Agent의 두 번째 명확성 gate 없이 Assessor로 전달되며, Assessor만 ESTABLISHED 또는 NOT_ESTABLISHED를 판정한다.
- NOT_ESTABLISHED는 확인 질문을 반복하거나 AFTER를 발명하지 않고 일반 CBT 질문·지지로 돌아간다. ESTABLISHED만 같은 Agent의 원문 충실도·왜곡 근거·유형 적합성 review를 거쳐 proposal이 된다.
- 일반 질문·도움·확인 질문은 SELECT 1회, 확인 뒤 NOT_ESTABLISHED는 SELECT → ASSESSOR 최대 2회, ESTABLISHED proposal은 SELECT → ASSESSOR → ASSESSMENT_REVIEW 최대 3회다.
- 활성 proposal 도움은 동일 proposal·`PROPOSAL_REVIEW`를 보존하고 외부 outcome을 `EXPLAIN_PROPOSAL`로 매핑한다. 새 현재 생각 확인은 proposal 검토 단계에서 시작하지 않는다.
- BEFORE, AFTER, USER evidence와 승인 전 proposal 의미는 `Mindot-CBT-Redesign-Contracts.md`를 따른다. 승인 뒤에만 기존 Spring 흐름이 AFTER와 왜곡을 저장한다.
- 입력 상한은 48,000 tokens다. 활성 `cbt_session_agent` 패키지만 제품 CBT 경로에 남는다.

## RESTORE에서 미해결 확인 질문 복원

라이브 runtime은 `pending_question_purpose`를 내부 메모리에만 유지하며 외부 DTO·snapshot·DB에는 저장하지 않는다. RESTORE에서는 Agent가 전체 시간순 대화를 뒤에서부터 읽어 가장 최신의 아직 답변되지 않은 현재 생각 확인 질문을 찾는다.

확인 질문 뒤에 USER의 도움·설명·예시 요청과 그에 대한 ASSISTANT 응답으로 이루어진 교환만 끼어 있다면 질문은 계속 미해결 상태다. 이후 USER가 실제 현재 생각을 답하면 확인 질문이 직전 ASSISTANT 메시지가 아니어도 즉시 Assessor로 전달한다. Agent는 답변의 명확성이나 긍정 여부를 다시 판정하지 않는다.

현재 안전 문제, 명시적 기능 제어·종료, 주제 포기·철회·전환, 일반 CBT 질문 또는 proposal 전이가 확인 질문 뒤에 있으면 이전 질문을 복원하지 않는다. 우선순위는 safety → explicit control → current help/example → current-thought continuation → ordinary dialogue다.

## 고정 prompt 계약

`mindot_ai/cbt_session_agent/prompts`가 runtime 정본이며 `docs/cbt-redesign/prompts` 사본은 byte-identical이어야 한다. `.strip()`한 UTF-8 텍스트 기준 Q14 값은 다음과 같다.

| 파일 | 문자 수 | SHA-256 |
|---|---:|---|
| `agent.txt` | 2977 | `93f83c38fa25fea15008aa311b5923c583898ce404719a6210303305dda9c237` |
| `assessor.txt` | 1374 | `c3bc6e20479ce0d167c595e43e03e6030c854e20ae2af144e762cb89dae986f5` |
| `assessment-review.txt` | 660 | `878a5c2444a27fcbc24de729fa8a8afd83a10959885f6ecd6de86f75077f7779` |

runtime과 문서의 `model-contracts.json` 사본도 byte-identical이어야 한다.

## 재리뷰 범위

1. `app.py → cbt_session_agent package → cbt_session_agent.py facade → service.py`의 실제 import와 호출 closure.
2. NEW/RESTORE/TURN, 시간순 메시지 누적, revision, 동일 request replay/conflict, failure retry의 원자성.
3. 현재 생각 확인 직후 답변과 help 교환 뒤 답변이 Assessor로 이어지는지, RESTORE에서도 동일한지.
4. NOT_ESTABLISHED 뒤 비반복 대화 복귀와 ESTABLISHED proposal 생성·기존 승인 흐름.
5. safety·명시적 control·주제 포기/전환이 과거 확인 질문을 잘못 복원하지 않는지.
6. Assessor가 유일한 AFTER 판정자이고 final review가 두 번째 의미 adjudicator가 아닌지.
7. prompt/schema 사본, 정적 checker의 Q14 문자 수·SHA, review runner의 테스트 소스 범위.
8. 기록·패턴·인증·보고서·PDF·검색 등 비 CBT 기능의 import/route 영향.
9. `mindot_front`, `mindot_back`, Spring 운영 코드·Spring 테스트와 DB에 이번 수정 diff가 없는지.
10. 외부 reflection route, 요청/응답 DTO와 outcome enum이 유지되는지.

## 변경·실행 제한

이번 수정과 다음 리뷰에서 React/Frontend, Spring/Backend, DB 스키마·데이터·제약조건과 Spring 테스트는 변경하지 않는다. 새 외부 route·DTO 필드·outcome/state enum이나 별도 영속 상태를 추가하지 않는다.

다음 전체 소스 리뷰 전에는 pytest나 기타 테스트, `insight_static_check.py`, `insight_review_runner.py`, 앱/server, 실제 LLM/API, canary, 봉인 평가와 유료 평가를 실행하지 않는다. 허용되는 확인은 소스 읽기, `git diff --check`, git 상태, JSON 파싱, Python AST 구문, 사본 비교와 SHA·문자 수 확인뿐이다.

다음 평가는 Q10이 아니라 Q12/Q13/Q14 세 버전 비교다. 재리뷰가 테스트 진입 가능 여부와 정확한 실행 범위를 결정한다.
