# Mindot CBT 대화 구조

## 목적

짧은 감정 기록의 최초 자동적 생각을 시간순 대화로 구체화하고, 사용자가 그 판단의 잘못을 알아차려 생각을 수정한 경우에만 확인용 proposal을 제시한다. 의료 진단·치료 또는 상담을 대신하지 않는다.

## 현재 생성 흐름

```text
NEW / TURN
  └─ deterministic current-danger check
      └─ Agent SELECT
          ├─ ask_question(text) ───────────────→ QUESTION / DIALOGUE
          ├─ check_current_thought({}) ────────→ 서버 고정 QUESTION / DIALOGUE
          │   ├─ USER 실제 답변 ──────────────→ assess_completion
          │   └─ help 요청·응답만 경유
          │       └─ 이후 USER 실제 답변 ─────→ assess_completion
          ├─ offer_help(text)
          │   ├─ active proposal 없음 ───────→ HELP / DIALOGUE
          │   └─ active proposal 있음 ───────→ EXPLAIN_PROPOSAL / PROPOSAL_REVIEW
          ├─ respond_control() ───────────────→ CONTROL / DIALOGUE
          ├─ respond_safety(action, reason) ─→ SAFETY_* / DIALOGUE
          └─ assess_completion(fallbackQuestion)
              └─ Assessor candidate
                  ├─ NOT_ESTABLISHED ─────────→ QUESTION / DIALOGUE
                  └─ ESTABLISHED → same Agent review
                      ├─ accept ──────────────→ PROPOSAL / PROPOSAL_REVIEW
                      └─ reject ──────────────→ QUESTION / DIALOGUE
```

일반 질문·도움은 Agent 한 번이 의미와 최종 표시 문장을 작성한다. 현재 생각 확인은 Agent가 시점만 선택하고 서버가 `그렇다면, 처음에 떠올랐던 판단을 지금은 어떻게 보고 있나요?`를 고정 표시한다. Writer와 Writer repair 단계는 없다. 현재 생각 확인 뒤 Assessor가 NOT_ESTABLISHED로 판단하면 해당 턴은 SELECT와 ASSESSOR의 최대 2회, ESTABLISHED 후보를 검토하면 최대 3회 생성한다.

## 질문과 도움

질문은 자동적 생각과 최신 답변에서 아직 확인되지 않은 연결 하나를 고른다. 이미 말한 사실·원인·답이나 거절한 방향을 표현만 바꾸어 반복하지 않는다. 관찰 사실, 판단을 지지하거나 흔드는 구체적 근거, 예외 경험처럼 사용자가 바로 답할 대상을 분명히 한다.

사용자가 최초 자동적 생각을 재고했을 합리적인 신호를 보이면 Agent는 명확/가능 단계를 나누지 않고 `check_current_thought({})`를 한 번 선택한다. 서버 고정 질문은 후보 AFTER·왜곡 유형·이미 답한 근거를 제시하지 않으며 사용자 원문을 보간하지 않는다. 바로 답하거나 도움·설명·예시 교환만 거친 뒤 답한 USER 발화는 Agent의 두 번째 명확성 gate 없이 Assessor로 전달한다. Assessor만 AFTER의 ESTABLISHED/NOT_ESTABLISHED를 결정하며, 같은 Agent의 마지막 review는 원문 충실도·근거·왜곡 적합성만 확인한다.

도움은 직전 질문 또는 현재 proposal을 쉬운 말로 설명한다. 가상 예시는 가상임을 밝히고 사용자 경험·정답·생각 변화의 근거로 저장하지 않는다. proposal 설명은 내용을 바꾸거나 동의·저장을 대신 확정하지 않는다.

## proposal 상태 전이

- 설명 요청: 같은 proposal을 보존한다.
- 실제 정정·변화 철회·새 탐색: 기존 proposal을 제거하고 새 질문으로 진행한다.
- 새로운 실제 변화가 성립한 completion: Assessor와 최종 검토를 거쳐 새 proposal로 교체한다.
- 사용자 confirm 전: 언제나 미확정 제안이다.

## 메모리와 복원

Spring이 전체 상태의 영속 정본이다. AI runtime은 NEW/RESTORE/TURN과 revision·request ID를 검사하며 성공한 assistant 메시지만 commit한다. RESTORE는 생성 없이 전체 상태를 복구하고, TURN의 새 USER delta는 정확히 한 번만 누적한다.

현재 생각 확인 목적은 라이브 runtime에만 보관하며 route·DTO·snapshot·DB에 새 상태를 추가하지 않는다. RESTORE에서는 이 휘발성 마커를 지우고, Agent가 전체 시간순 대화를 역추적해 가장 최신의 미해결 현재 생각 확인 질문을 찾는다. 질문 뒤에 도움·설명·예시 요청과 응답만 끼어 있으면 이후 실제 답변과 결속하며, 안전·명시적 제어·주제 포기/전환·일반 질문·proposal 전이가 있으면 복원하지 않는다. 질문 목적은 Assessor로 보내는 routing 정보일 뿐 AFTER 성립 판정이 아니다.

## 안전과 중단

좁은 deterministic detector는 명확한 현재 즉시 위험만 선차단한다. 과거·부정·가정·인용 위험은 자동 중단하지 않는다. 그 밖의 문맥 판단은 Agent의 `respond_safety`가 담당한다. 중단 안내는 나중에 이어하기와 성찰 완전 종료를 구분하며 AI가 사용자의 영구 종료를 대신 확정하지 않는다.

## 삭제된 비활성 구현

`cbt_agent.py`, `cbt_q5`, `cbt_q11`과 그 내부 Writer 기반 구현은 활성 소스에서 삭제했다. 과거 평가 재현에 필요한 동결 사본은 `mindot_ai/artifacts`에서만 보존한다.
