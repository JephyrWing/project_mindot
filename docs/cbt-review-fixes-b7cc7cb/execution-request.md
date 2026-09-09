# Codex 후속 수정 지시 — b7cc7cb 전체 리뷰 반영

저장소 `JephyrWing/project_mindot`, 원격 브랜치 `fix/CBTAI`의 `b7cc7cba4bf33ecc7361e32c06bde834206cd3ad`에 대한 ChatGPT 전체 소스 리뷰 결과는 **CHANGES_REQUIRED**다. 동봉한 `Mindot-CBT-Full-Branch-Review-b7cc7cb.md`와 `review.json`을 읽고 아래 세 기능 연결을 수정한다. 이전 구현 지시가 적용되지 않았다고 가정해 전체를 다시 재구축하지 말고, 실제 작업 트리와 원격 SHA를 먼저 확인한다. 팀원 변경과 미커밋 작업은 보존한다.

## 1. 수정 범위

**F1: 첫 시작의 기록 확정과 OPEN 재시도를 분리한다.**

`CBT.jsx`에서 PARTIAL 기록 확정은 성공했지만 OPEN이 실패하면 같은 화면에서 다시 COMPLETE 기록 확정을 요청하여 409가 발생한다. 확정 뒤에는 OPEN만 재시도하게 한다. 확정 응답 유실은 기록 재조회로 복구한다. 실제 저장된 생각과 충돌하는 변경을 성공으로 오인하지 말고, 모든 409 무시나 COMPLETE 덮어쓰기로 해결하지 않는다. OPEN 멱등 키를 유지한다.

**F2: 구형 OPEN 세션에서 저장된 답변의 생성 재시도를 보존한다.**

구형 ReflectionSessionTurnTransactionService는 답변 저장 후 QUESTION job 실패를 재시도할 수 있었다. 새 InsightTransactions는 insight.lastJobId만 읽으므로 이 구형 상태를 복원해도 retry가 사라진다. 구형 저장 이력과 job 상태를 읽어 실패/진행 상태를 구별하는 최소 호환 처리를 구현한다. 같은 USER를 두 번 추가하지 않고 명시적 RETRY가 저장된 입력을 처리하도록 한다. NEW/RESTORE/TURN의 revision 및 이력 경계도 맞춘다. 정상 구형 재개·구형 성공 제안·종료 상태를 실패로 오인하지 않는다. RESTORE 자체로 생성 호출을 하지 않는다. 구형 엔진 재활성화, DB 일괄 변환, migration 도구/스크립트, 데이터 삭제는 하지 않는다.

**F3: 확정 AFTER 사례와 수락 유형 집계를 분리한다.**

EmotionRecordsService.findPatternSimilarCases의 빈 confirmedDistortionCodes 필터 때문에 모든 유형을 거부한 유효한 AFTER까지 검색에서 제외된다. 기존 소유권·완료·도움 점수·날짜 조건을 유지하면서 사용자 확정 AFTER를 전달한다. 반복 유형 집계는 수락 코드만 사용하고 유형이 없을 때는 없음으로 표시한다. 거부 유형을 복권하거나 없는 라벨을 만들지 않는다. DailyCare, 기록 상세, AI pattern_explanation, 임베딩/검색의 소비까지 확인한다. 구형 데이터의 의미는 유지한다.

## 2. 유지할 설계

- 맥락에 맞는 CBT 질문을 최우선으로 한다. Agent/Writer/Assessor와 같은 Agent의 최종 검토를 유지한다. 이번 세 문제를 이유로 의미 검증 호출·ledger·강제 네 영역 채우기·Assessor 보충 질문을 추가하지 않는다.
- BEFORE는 원래 자동사고다. AFTER는 사용자가 그 판단의 잘못을 깨닫고 수정한 생각을 AI가 대화에 근거해 정리한 것이다. 단순 현재 생각이나 동의만으로 제안하지 않는다. 사용자가 AFTER와 유형 검토 결과를 승인해야 확정 저장한다. 모든 유형 거부도 가능하다.
- Spring 영구 이력, NEW/RESTORE 전체 복원, TURN delta와 AI 메모리 누적, 입력 선저장·결과 원자 확정, 멱등성·revision·timeout·취소 뒤 늦은 결과 차단을 유지한다.
- 나중에 이어하기는 OPEN, 완전 종료는 CANCELLED, 승인 완료는 COMPLETED, 안전 중단은 SAFETY_STOPPED다.
- 입력 상한 48,000 tokens/196,608 UTF-8 bytes 및 현재 호출/출력 예산을 이번 수정과 무관하게 축소하거나 확대하지 않는다. 기존 프롬프트·채점표·봉인 평가 기대값을 바꾸지 않는다.

## 3. 검토와 제출 순서

이번에는 **수정 → 원격 fix/CBTAI push → ChatGPT 전체 브랜치 리뷰 → 테스트** 순서다. 현재 CHANGES_REQUIRED를 자체 PASS로 바꾸어 테스트에 진입하지 않는다. 오프라인 테스트·canary·유료 모델 평가를 실행하지 않는다. 필요하면 기존 구현 단계에서 허용했던 구문·컴파일·빌드 확인만 수행하고 테스트 실행과 명확히 구별해 보고한다.

수정 후 세 부분의 diff뿐 아니라 시작/재개/저장/승인/종료, 구형·신형 기록 상세, 보고서/PDF, 검색 및 관련 호출자를 다시 읽어 후속 영향을 확인한다. 리뷰의 비차단 관찰 사항은 별도로 기록하며 canary 조건을 늘리거나 무관한 재설계로 확장하지 않는다.

완료하면 다음을 제출한다.

- 시작 SHA, 최종 로컬 SHA, push 후 원격 SHA와 일치 확인. 강제 push는 하지 않는다.
- F1–F3별 수정 파일·변경 이유·사용자 흐름·후속 소비자 검토 결과.
- 전체 tracked 목록 및 현행 자사 소스/설정/테스트/문서의 hash 원장, 최신 소스 ZIP. 과거 raw/비밀값은 새 실행 자료에 섞지 않는다.
- 실제 실행 명령·결과 및 `NOT_RUN_PENDING_GPT_FULL_BRANCH_REVIEW` 테스트 상태.

push와 결과 저장 후 멈춘다. ChatGPT가 최종 원격 커밋을 읽고 READY_FOR_TEST를 내린 뒤 테스트 단계로 넘어간다.
