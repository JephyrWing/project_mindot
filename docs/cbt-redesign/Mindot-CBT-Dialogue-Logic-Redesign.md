# Mindot CBT 대화 구조

## 목적

짧은 감정 기록의 최초 자동적 생각을 시간순 대화로 구체화하고, 사용자가 그 판단의 잘못을 알아차려 생각을 수정한 경우에만 확인용 proposal을 제시한다. 의료 진단·치료 또는 상담을 대신하지 않는다.

## 현재 생성 흐름

```text
NEW / TURN
  └─ deterministic current-danger check
      └─ Agent SELECT
          ├─ ask_question(text) ───────────────→ QUESTION / DIALOGUE
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

일반 질문과 도움은 Agent 한 번이 의미와 최종 표시 문장을 작성한다. Writer와 Writer repair 단계는 없다. 완료 경로만 최대 3회 생성한다.

## 질문과 도움

질문은 자동적 생각과 최신 답변에서 아직 확인되지 않은 연결 하나를 고른다. 이미 말한 사실·원인·답이나 거절한 방향을 표현만 바꾸어 반복하지 않는다. 관찰 사실, 판단을 지지하거나 흔드는 구체적 근거, 예외 경험처럼 사용자가 바로 답할 대상을 분명히 한다.

도움은 직전 질문 또는 현재 proposal을 쉬운 말로 설명한다. 가상 예시는 가상임을 밝히고 사용자 경험·정답·생각 변화의 근거로 저장하지 않는다. proposal 설명은 내용을 바꾸거나 동의·저장을 대신 확정하지 않는다.

## proposal 상태 전이

- 설명 요청: 같은 proposal을 보존한다.
- 실제 정정·변화 철회·새 탐색: 기존 proposal을 제거하고 새 질문으로 진행한다.
- 새로운 실제 변화가 성립한 completion: Assessor와 최종 검토를 거쳐 새 proposal로 교체한다.
- 사용자 confirm 전: 언제나 미확정 제안이다.

## 메모리와 복원

Spring이 전체 상태의 영속 정본이다. AI runtime은 NEW/RESTORE/TURN과 revision·request ID를 검사하며 성공한 assistant 메시지만 commit한다. RESTORE는 생성 없이 전체 상태를 복구하고, TURN의 새 USER delta는 정확히 한 번만 누적한다.

## 안전과 중단

좁은 deterministic detector는 명확한 현재 즉시 위험만 선차단한다. 과거·부정·가정·인용 위험은 자동 중단하지 않는다. 그 밖의 문맥 판단은 Agent의 `respond_safety`가 담당한다. 중단 안내는 나중에 이어하기와 성찰 완전 종료를 구분하며 AI가 사용자의 영구 종료를 대신 확정하지 않는다.

## 삭제된 비활성 구현

`cbt_agent.py`, `cbt_q5`, `cbt_q11`과 그 내부 Writer 기반 구현은 활성 소스에서 삭제했다. 과거 평가 재현에 필요한 동결 사본은 `mindot_ai/artifacts`에서만 보존한다.
