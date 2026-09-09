# 전달 명세 검토

2026-09-09 · simple-dialogue-2. 실제 제품 구현/실행 검토와 구분한다.

| 검토한 문제 | 반영 | 후속 영향 |
| --- | --- | --- |
| canary가 요구한 자유 초안 생성 | 입력과 필수조건 제거, 기존 CBT 확인 결과와 구분 | 전용 문서/요약 도구 없음, 근거 있는 자동 평가 허용 |
| 명백히 상충하는 공개 gold | known-scope-2와 수정 이력 | 양쪽 동일 적용, 기존점수 직접 비교 불가 |
| 미탐색 영역의 기계적 질문 강제 | 10개 복수 허용 행동으로 사전 정의 | 전체 품질/368수집 유지, 단일 class 진단 대상 차이는 공개 |
| 정상 부모 확인 뒤 고정 후속을 실패 처리 | NOT_APPLICABLE_PARENT_CONFIRMATION | 후속은 성공이 아니라 미관측, 가짜 질문/추가 호출 없음 |
| 일반 write_turn target 혼동 | 해당 인자만 제거, NORMAL은 move로 purpose/route 생성 | 이전 목적 덮어쓰기까지 제거, help/WAIT/gap/정정 대상 유지 |
| 최신 발화와 과거 plan 혼재 | 실제 현재 발화 별도 메시지·공개 대화 투영 | START/cold/수정·다음 phase 원문 접근 보존 |
| source text 위치 모순 | 전체 입력에 한 번, 최신 source는 마지막 메시지 | checkpoint 원본 불변, 새 요약 모델 없음 |
| 응답 불일치를 기술 오류로 집계 | execution/acceptance 분리 | 이전 raw는 원래 값 유지, 리뷰만 정정 |

schema는 일반 target만 제거한 다섯 도구다. GOAL의 gap 답변·철회 대기, 실제 요청 이행·정정·중단/안전·원자적 저장과 호출3/Moderation1을 유지한다. 완성 prompt5개 중 Agent/Writer를 변경하고 Assessor/review/repair는 그대로 유지했다. 새로운 runtime 의미 gate나 LLM을 추가하지 않았다.

canary12, 공개 단일160/장기10을 검토했다. 공개 단일 사용자 발화에는 자유 초안 명령이 없었으며 상충 expected와 과도한 내부 gate가 주요 문제였다. 공개 입력160은 보존하고 장기1의 자발적 답변만 구체화했다. 봉인14는 열지 않았으므로 전체184의 품질을 전수 확인했다고 하지 않는다.

최종 artifact-checks는 schema 구조·참조·수량·patch 재현·hash를 확인한 문서 검사다. 제품 오프라인·live provider acceptance·질문 의미 성공이 아니다. 실제 기능 개선과 수정 모델의 canary 통과는 구현 후 확인해야 한다.
