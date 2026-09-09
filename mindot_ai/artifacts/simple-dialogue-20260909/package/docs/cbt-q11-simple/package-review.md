# 작성자 검토 기록

2026-09-09 · `simple-dialogue-1` · 새 제품 미구현·미실행.

## 확인한 근거

- 중단 REPAIR-13의 공개 CbtTurnResponse/ReflectionOutcomeDraft, completion·policy·memory·rendering·Move 정의와 기존 실행 패키지를 읽었다.
- sourceId 직접 연결만 바꾸었던 question-first-1이 과도한 per-source 분석 의무·네 영역 완료 gate·문체 검사를 유지한 문제를 확인하고 active 계약에서 제거했다.
- 공개 status는 CONTINUE/CONFIRM_REQUIRED/SAFETY_STOP이며 근거/acknowledgement nullable, nextQuestion 500자다. UI의 취소 동작 자체는 기존 안내를 사용한다. Spring/React 실소스의 소비 동작은 이 작성 환경에서 확인하지 못했으며 실행 Codex가 읽기 전용으로 확인한다.
- 첨부 워크시트의 모든 질문을 모든 생각에 적용하지 않는 원칙을 확인했다. 제품 효능·모델 성공률의 검증 근거로 확대하지 않는다.

## 수정한 설계 충돌

1. 모든 입력 답변의 분류가 끝나야 질문을 만드는 구조 대신, 원문→선택적 상태 변화→실제 도구를 사용한다. 상세 근거 추출은 Assessor에만 둔다.
2. coverage 관찰과 평가 적격성을 분리한다. 정직한 미판정 내부 후보는 기존 CONTINUE 제어 안내로 매핑하고 no-clear를 대체값으로 쓰지 않는다.
3. Writer/Assessor gap/renderer에 남은 동일 문체 검사를 함께 제거하도록 했다. 출처·정정·멱등성·예산·공개 DTO 무결성은 유지한다.
4. 새로운 정상 경로에서 안전/인자 전용 추가 phase를 제거하고, 같은 Agent의 완료 검토와 Writer 형식 복구만 각각 필요한 경로에서 유지한다.
5. canary를 실제 질문에 이어지는 두 3턴 대화와 여섯 기본 사례로 바꿨다. 자발적 추가 설명이 질문의 정답과 다르다는 이유로 pre-dispatch 의미 gate를 두지 않는다.
6. 내부 phase 예상표를 품질 gate와 분리했다. 유효한 사실 범위 질문과 근거 있는 한정 no-clear를 사전에 함께 허용해 branch를 강제하지 않는다.
7. raw-export와 rubric의 SUMMARY_ONLY/USER_STOP·null coverage를 맞췄다. rubric v1.11은 실질적 해석 변경이며 동일 비교 양쪽에 적용한다. 배점/집계 산식/안전 다섯 항목과 원본 known/hidden 기대값은 유지한다.

## artifact 자체 검증

schema reference는 고정형 SELECT5종과 Writer/Assessor/review/repair schema다. 로컬 JSON Schema 구조와 대표 유효/무효 인자를 확인했다. source100개를 참조해도 schema 자체가 늘지 않고, Writer의 줄바꿈/다른 종결/물음표 없는 문장이 구조상 허용되며 공개500자 한도는 남는다. 이것은 제품 호출이나 의미 수행 검증이 아니다.

SELECT schema compact UTF-8 15,779 bytes / 로컬 o200k_base 추정3,998 tokens. Assessor schema3,770 bytes/964tokens. 원래 요청과 동일 조건에서 제품을 실행한 비교가 아니므로 토큰 절감률·정확도 향상을 확정하지 않는다. 실제 provider 수락·프레임워크 통합·질문 품질은 Codex의 실제 검증 대상이다.

제공 prompt 전문은 Agent2,054자, Writer773자, Assessor1,499자, 같은 Agent 최종 검토415자, Writer repair266자다. 끝의 LF를 제외한 Unicode codepoint 기준이다. 실행본은 제공 파일의 마지막 LF 하나만 제거해 사용하고, 실제 전송문·schema·usage를 잠근다. prompt를 다시 길게 덧붙이는 처방을 하지 않는다.

## 실질적인 잔여 불확실성

- 원문 이해·질문 선택·정정 해석은 여전히 모델이 해야 한다. 구조 단순화가 canary 통과를 보장하지 않는다.
- 최종 Agent가 후보를 실질 오류로 거절하면 이 턴은 기술 실패로 남는다. 추가 네 번째 호출이나 근거 없는 질문으로 숨기지 않는다.
- 전체 raw를 중복 없이 제공하므로 길이가 매우 긴 세션은 입력 한도에 걸릴 수 있다. 지원 범위 확대·요약/검색 체계는 이번 변경에 넣지 않는다.
- 실제 checkpoint 없는 cold 복원에서 과거의 정밀 receipt/budget/lineage를 전부 복원했다고 주장하지 않는다. 원문과 현재 사용자 의도에 따른 정상 진행 가능 범위를 구분한다.
- rubric의 새 품질 해석과 frozen legacy 기대 label이 충돌할 수 있다. 기존 분모·classification을 그대로 산출하고 별도 해석한다. 새 candidate를 유리하게 만드는 재라벨은 없다.

이 기록은 작성자 및 보조 검토의 설계/파일 검토다. 제품 테스트 통과·공식 품질 점수·새 candidate source 인증이 아니다.


## 입력 용량 규칙 후속 정합화 — 2026-09-09

작성 규칙v1.20에 48,000 tokens/196,608 bytes를 지속 적용 기본값으로 명시했다. REPAIR-13 llm.py의 실제 적용값을 확인했고, 과거 임시 승인 발언의 정확한 문구를 새로 추정하지 않았다. 현재 사용자의 규칙 반영 지시가 이 정식화의 근거다. 일반 문자 수 기준과 전체 API 입력을 구분하고, 정상 요청에 부족하면 첫 canary 전 측정 후 모델/총량 한도 안에서 조정할 수 있게 했다. design의 ‘더 큰 용량을 추가하지 않는다’ 고정 문구도 함께 교체했다.

canary1,750,000은 누적 중단 예산으로 유지하며 모든 요청 최대 조합의 실행을 보장하지 않는다. actual 요청 추정으로 예약·정산하고 BUDGET_BLOCKED와 입력 용량 실패를 구분한다. 평가 원본·rubric·5개 제품 prompt·schema는 변경하지 않았다. 제품 실행과 유료 호출은 없다.

사용자의48k 적정성 우선 검토 지시에 따라 기존 UNIT 최대와 REPAIR-13 actual/local usage를 대조했다. 32k/48k/64k 비교 결과48k/192 KiB를 유지한다. 공식128k context와 예약 보정1.75, 최대출력8,192를 함께 확인했다. 판단 근거 문서를 포함하고 기본값 충분 시 추가 용량 최적화/시험을 요구하지 않도록 명시했다.


## 최초 전달 설명 정정 — 2026-09-09

사용자는 앞선 재설계 지시를 아직 Codex에 전달하지 않았다. 이번 main과 ZIP만으로 마지막 중단 코드에서 직접 구현한다. 필수 명세 누락은 발견하지 않았으며, 전달/적용 완료로 오해될 문구를 바로잡았다. 모델 prompt·schema·canary·용량·공식 입력은 변경하지 않았다.
