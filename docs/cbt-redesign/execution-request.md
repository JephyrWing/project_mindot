# 다음 단계: GPT 전체 소스 리뷰 요청

원격 `fix/CBTAI`의 최신 SHA를 고정한 뒤 전체 소스를 읽어 현재 CBT 단순화와 주변 기능 영향을 검토한다. 이 문서는 구현 재실행 지시가 아니며 Writer 기반 과거 설계를 복원하지 않는다.

## 현재 기준

- 일반 질문·도움은 Agent SELECT 한 번이다.
- 도구는 `ask_question(text)`, `offer_help(text)`, `assess_completion(fallbackQuestion)`, `respond_control()`, `respond_safety(action, reason)`다.
- 완료만 `SELECT → ASSESSOR → 같은 Agent ASSESSMENT_REVIEW` 최대 3회다.
- 활성 proposal 도움은 동일 proposal·`PROPOSAL_REVIEW`를 보존하고 외부 outcome을 `EXPLAIN_PROPOSAL`로 매핑한다.
- 정정·변화 철회·새 탐색은 stale proposal을 제거하거나 새 completion으로 교체한다.
- BEFORE, AFTER, USER evidence와 승인 전 proposal 의미는 `Mindot-CBT-Redesign-Contracts.md`를 따른다.
- 입력 상한은 48,000 tokens다.
- 활성 `cbt_session_agent` 패키지만 제품 CBT 경로에 남고 `cbt_agent.py`, `cbt_q5`, `cbt_q11`은 삭제됐다.

## 리뷰 범위

1. `app.py → cbt_session_agent package → cbt_session_agent.py facade → service.py`의 실제 import와 호출 closure.
2. NEW/RESTORE/TURN, revision, 동일 request replay/conflict, failure retry의 원자성.
3. 질문·도움 1회와 proposal 3회 경로, 예약·출력 cap·SDK retry.
4. proposal 설명 보존과 정정·철회 시 제거.
5. BEFORE/AFTER, 실제 USER evidence, Assessor와 같은 Agent 검토, 승인 전 미확정 의미.
6. 안전, 나중에 이어하기와 완전 종료 구분.
7. 정적 checker와 review runner의 현재 suite 범위 및 fail-fast 동작.
8. runtime model-contract/prompt와 고정된 Q13 prompt 문자 수·SHA-256의 일치.
9. 기록 생성·목록·수정·삭제, 패턴, 인증, 보고서, PDF, 검색 등 비 CBT 기능의 import/route 영향.
10. `mindot_front`, `mindot_back`, DB와 외부 DTO/route에 이번 commit의 diff가 없는지 확인.

## 실행 제한

이 리뷰 단계에서는 테스트, `insight_static_check.py`, `insight_review_runner.py`, 앱/server, canary, 유료 API와 공식 평가를 실행하지 않는다. 소스를 읽어 결함·누락·회귀 위험을 보고하고, 다음 실행 범위는 별도 지시로 결정한다.

리뷰 결과에는 고정한 SHA, 읽은 manifest, 활성 import closure, 발견 사항의 우선순위와 근거, 미확인 위험을 포함한다. 과거 Writer 문서나 `cbt_q11` 구현을 현재 계약으로 간주하지 않는다.
