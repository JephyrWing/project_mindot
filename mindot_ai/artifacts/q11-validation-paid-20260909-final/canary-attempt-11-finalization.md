# Canary attempt 11 / retry10 — 최종 실패 감사

상태 CANARY_BLOCKED. Journal 91개 체인과 canonical 결과가 일치하며 기존 원본은 바꾸지 않았습니다.
사용자의 마지막 지시에 따라 이후 제품 수정·재시도·공식 평가는 진행하지 않습니다.

| 실제 케이스 | 보존한 개발 review | 실제 경로 / 결과 | 확정 tokens |
|---|---|---|---:|
| example-only | PASS, 예시 다양성은 제한적 | SELECT → ARGUMENT_REPAIR → WRITER; CONTINUE·state commit, atoms 0·coverage 미탐색 | 20,421 |
| complete-substantive-example | FAIL | SELECT의 도움 구절은 잘못된 slot_000/FOR_1; invalid_occurrence로 batch 거절; Writer·공개 응답·accepted state 없음 | 26,381 |

첫 케이스 SELECT는 하나의 signal만 생성하면서 두 예약 alias를 선택했습니다.
실제 ARGUMENT_REPAIR 응답이 requestIds를 존재하는 slot_000:signal_0 하나로 줄인 뒤 Writer가 호출됐습니다.
이는 기록된 모델 repair이며 서버가 raw를 다시 쓰거나 새 사실을 만든 결과가 아닙니다.

두 번째 SELECT의 네 contributions 배열은 모두 비어 있었습니다.
최신 도움 구절의 원문은 slot_003/ACK_4이지만 signal은 slot_000/FOR_1에 놓였습니다.
presentation target은 ACK_4를 가리키지만 signal target은 FOR_1로 서로 불일치합니다.
requestIds의 slot_003:signal_0/1은 입력 source-local metadata에 예약돼 있을 뿐 실제 output signal로 생성되지 않았습니다.
validator가 전체 batch를 거절했으므로 이 제안을 승인된 기록이나 성공한 CONTENT 추출로 보고하지 않습니다.
기존 FAIL·raw·source·target을 수정하지 않았습니다. PASS 후 두 번째 case를 시작했고 FAIL 뒤에는 더 호출하지 않았습니다.
남은 7건은 원본 NOT_RUN_CANARY_BLOCKED이며 공식 평가는 0/368, NOT_RUN입니다.

- 이번 생성 4회 + Moderation 2회, HTTP 200 여섯 응답 확인.
- 이번 실제 입력 46,295 + 출력 507 = 46,802 tokens.
- 누적 11차례 dispatch: 생성 31회 + Moderation 16회.
- 누적 확정 입력 300,894 + 출력 4,461 = 305,355 tokens.
- 다섯 번째 전송 실패의 UNKNOWN actual usage 및 예약 18,806은 유지됩니다. 324,161은 확정+예약 회계합계일 뿐 실제 사용·청구 합계가 아닙니다.
- Lock 608/608 일치. 실제 REPAIR-13 source 50/50, reused frozen-runner-retry04 50/50 일치.
- REPAIR-13 오프라인 함수 121/121와 subtest 516 통과 영수증 해시를 대조했습니다. 오프라인 통과를 live 성공으로 표현하지 않습니다.
- 실제 phase prompt hash를 원본 상수의 정적 AST 읽기 및 각 진단과 대조했습니다. 제품 코드를 import/실행하지 않았습니다.
- 이전 열 차례의 고정 보고서·lock·claim·journal/raw 또는 오류 기록을 재검증했습니다.

Worker 25229 exit 0은 부모 도구 관측이며 canonical finish는 독립 검증했습니다.
감사 API·키·hidden 평문 읽기 0; source/raw/claim/lock/canonical 수정 0.
ZIP·Drive 전달·최종 상태 파일·PC 종료는 부모 작업이며 이 감사가 실행했다고 주장하지 않습니다.
상세 해시는 canary-attempt-11-finalization.json.
