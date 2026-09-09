# Q11 수리·검증·실제 canary 전체 이력

기준 시각: 2026-09-09 00:55:43 KST / 2026-09-08 15:55:43 UTC. 대상: 최초 검토 후보 Q11부터 마지막 REPAIR-13까지.

최종 상태는 **CANARY_BLOCKED**입니다. 마지막 REPAIR-13은 오프라인 함수 121/121·subtest 516·compile 47/47 및 adapter 10/10을 통과했으나, 실제 마지막 시도에서 첫 케이스 PASS 후 두 번째 케이스 FAIL로 중단됐습니다. 나머지 canary 7건과 공식 평가 368건은 실행하지 않았습니다. 사용자의 마지막 지시에 따라 더 이상 제품 수정이나 유료 재시도를 진행하지 않습니다.

이 문서는 개발 수리 및 실행 감사 기록입니다. 공식 비교 평가·임상적 유효성·모델 성능 합격을 뜻하지 않습니다. [기계 판독 manifest](repair-history-manifest.json), [마지막 실제 canonical 결과](../live/mindot_ai/canary-retry10/canary-result.json), [최종 독립 감사](canary-attempt-11-finalization.md)가 근거입니다.

## 1. 증거 범위와 읽는 방법

- 동결 스냅샷 17개를 비교했고 실행 디렉터리의 총 840개 파일을 해시 확인했습니다. 각 repair manifest가 선언한 파일과 불일치한 경로는 0개입니다. 반복 스냅샷에 같은 파일이 있으므로 840은 고유 제품 파일 수가 아닙니다.
- 최초 검토 manifest는 43개 파일을 선언합니다. 최초 실행 폴더의 45개 파일 중 나머지 둘은 `Dockerfile`, `requirements.txt` 선언 파일입니다. 최초 source-set hash를 45파일 기준으로 임의 재정의하지 않았습니다.
- 실제 결과 영수증 37개를 수집했습니다: 전체 suite 17개, adapter suite 17개, 표적 진단 suite 3개. 통과하지 못한 원 영수증을 보존하고 후속 통과 결과와 분리했습니다.
- 파일별 전후 SHA, 추가/변경 구분, 제품/테스트/선언 분류, 스냅샷·시험·실제 시도별 경로와 SHA는 기계 manifest에 있습니다. source-set digest는 각 동결 receipt의 정의를 그대로 인용하며 개별 파일은 별도로 해시 확인했습니다.
- 아래 “실제 시도 번호”는 전체 1–11번입니다. 작업 디렉터리 이름은 최초 `canary`, 이후 `canary-retry01`–`canary-retry10`이므로 숫자가 하나 어긋납니다. transport 재시도도 별도 실제 시도로 계산합니다.
- 본 이력 작성 중 제품 실행·시험 재실행·모델/API 호출·키 및 hidden 평문·평가 rubric 읽기는 없었습니다. source/raw/기존 lock/claim/receipt를 바꾸지 않았습니다.

## 2. 동결 스냅샷별 무엇을 왜 바꿨는가

“제품+관련 회귀”에는 코드·schema 설명·prompt만 바뀐 경우도 포함됩니다. 정확한 파일 단위 종류는 기계 manifest의 `changedFromPrevious`에 있습니다. “테스트만”은 해당 직전 snapshot과 비교해 제품 bytes가 동일함을 확인한 구분입니다.

| 동결본 | 변경 범주 | 선언 파일 | 변경 내용과 이유 | 사용한 실제 시도 |
|---|---|---:|---|---|
| [Q11](../snapshot-verification.json) | 원 후보 | 45 | 정적 리뷰를 마친 원 후보를 실제 고정 환경에서 처음 실행. 용량 admission, fixture DTO/예약 코드, 오래된 SELECT hash 기대값 등에서 중단. | 없음 |
| [REPAIR-01](../snapshot-REPAIR-01.json) | 제품+관련 회귀 | 48 | 원문/의미를 보존하는 shared-schema 압축, request-local WI ID codec, 실제 SDK 직렬화 측정과 raw/decoded 경계 연결. 테스트의 한 글자·예약 코드, stale hash 등 확인된 fixture 오류 보정. 12,000 입력 상한은 이 단계에서 유지. | 없음 |
| [REPAIR-02](../snapshot-REPAIR-02.json) | 제품+관련 회귀 | 49 | 승인된 입력 상한을 32,000으로 확대. canonical JSON 복원 후 base semantic projection 순서가 달라지던 문제를 sourceKey 기준 표시 순서로 안정화(정정 인과 순서 불변). codec 보완 및 source edit/archive fixture 명시 binding 보정. | 없음 |
| [REPAIR-03](../snapshot-REPAIR-03.json) | 제품+관련 회귀 | 49 | 승인된 입력 상한 48,000/196,608 bytes 적용. SDK 실제 wire 숫자 표현까지 동일성 비교, 현재 정정에 빈 legacyIds를 생성해 immutable receipt가 변하던 문제 수정, 잘못된 request proof를 typed technical error로 보존. 독립 HTTP 요청 fixture deep copy와 관련 회귀 보완. | 1 |
| [REPAIR-04](../snapshot-REPAIR-04.json) | 제품+관련 회귀 | 49 | 첫 실제 canary의 GOAL_BINDING_UNRECOVERABLE_presentation 수습. 실제 public history 질문의 원문·purpose·route·identity를 질문 전용 provenance로 엄격 결속. 목표 fact binding UNKNOWN은 유지하고 일반 CONTINUE/REVISIT 허용을 넓히지 않음. warm migration/optional route omission 및 phantom requestIds 제한 복구 회귀. | 2 |
| [REPAIR-05](../snapshot-REPAIR-05.json) | 제품+관련 회귀 | 50 | Writer 기존 금지 형식을 provider schema pattern에도 동일 적용하고 repair_issue의 shape 분류만 호환. 독립 결함 검사와 길이/중복 복구 allowlist는 유지. EXAMPLE 일반 역할 설명 추가; 시스템 프롬프트 불변. | 3 |
| [REPAIR-06](../snapshot-REPAIR-06.json) | 제품+관련 회귀 | 50 | EXAMPLE을 기존 질문에 대한 명시적 가상 답변 두 개와 가까움/둘 다 아님 선택 질문으로 표현하도록 schema description 강화. 시스템 프롬프트·validator·실제 원문 불변. 이 설명 보강의 실제 효과는 다음 canary에서 실패. | 4 |
| [REPAIR-07](../snapshot-REPAIR-07.json) | 제품+관련 회귀 | 50 | 실제 Arabic U+061F로 빠져나간 물음표 금지 형식을 Unicode 이름에 근거한 유한 25문자 집합으로 provider/local/repair/render에 통일. 의미 질문 탐지나 자동 문장 치환 아님. 시스템 프롬프트 불변, 이 소스 자체의 paid 시도 없음. | 없음 |
| [REPAIR-08](../snapshot-REPAIR-08.json) | 제품+관련 회귀 | 50 | 사용자가 프롬프트 변경을 명시 승인한 뒤 Writer/Writer Repair 두 문자열만 모드별 출력 역할과 정보 목표를 구분. 나머지 다섯 phase·tool prompt 및 caps 불변. 같은 소스의 첫 시도는 transport UNKNOWN, 네트워크 실행환경만 복구한 다음 시도는 semantic FAIL. | 5, 6 |
| [REPAIR-09](../snapshot-REPAIR-09.json) | 제품+관련 회귀 | 50 | Writer 표현 전용 payload projection: outputFieldRoles/underlyingGoalContext 구분, source/validity/evidence/correction/안전 binding 보존, 의사결정용 lifecycle metadata와 반복 질문을 정리. primary Writer prompt에 현재 자료와 무관한 가상 형식 예시 추가. 첫 suite의 1 FAIL은 새 테스트의 pre/post commit 질문 ID 시점 혼동. | 없음 |
| [REPAIR-09B](../snapshot-REPAIR-09B.json) | 테스트만 | 50 | 동일 REPAIR09 제품에 대해 SAFETY parent episode의 pre-Writer snapshot과 실제 새 질문 commit을 시점별로 비교하도록 테스트만 수정. 다른 episode 필드는 exact equality 유지. | 7 |
| [REPAIR-10](../snapshot-REPAIR-10.json) | 제품+관련 회귀 | 50 | 실제 Agent의 잘못된 slot 구절 복사·실질 내용의 도움 요청 오분류 수습. Agent에 source-local mixed 절 지침, schema slot별 실제 sourceId 설명, policy의 slot mapping 전달 추가. Writer 제품 불변. 새 긍정 fixture가 complete 상태에서 write_turn을 요구해 정상적으로 거절됨. | 없음 |
| [REPAIR-10B](../snapshot-REPAIR-10B.json) | 테스트만 | 50 | 양성 façade fixture를 실제 incomplete 3답변으로 수정. 음성 fixture occurrence=null이 원한 invalid_occurrence보다 앞선 ambiguous_or_missing_excerpt를 내므로 아직 1 FAIL. 제품 및 validator 불변. | 없음 |
| [REPAIR-10C](../snapshot-REPAIR-10C.json) | 테스트만 | 50 | 음성 fixture의 occurrence를 실제 실패와 동일한 0으로 명시. source mismatch를 가리거나 validator를 완화하지 않음. 10/10B/10C 제품 bytes 동일. | 8 |
| [REPAIR-11](../snapshot-REPAIR-11.json) | 제품+관련 회귀 | 50 | SELECT-only provider view의 기존 source 행에 reviewSlot/sourceQuestionPendingId 추가. 실제 mapping에만 근거하고 두 annotation을 제거하면 원 view로 복원. Agent에 일반 source-local 예시 추가. 모델의 네 CONTENT 제안은 개선됐지만 잘못된 help slot/target은 계속 실패. | 9 |
| [REPAIR-12](../snapshot-REPAIR-12.json) | 제품+관련 회귀 | 50 | normal review properties/required 표시 순서를 contributions→signals로 변경하고 Agent에 자기 원문 CONTENT 후 signals 지침. 필드 집합/타입/한도/validator 불변. 실제 순서 변화가 전송·출력까지 전달됐으나 help binding 실패는 해소하지 못함. | 10 |
| [REPAIR-13](../snapshot-REPAIR-13.json) | 제품+관련 회귀 | 50 | SELECT에서 아직 생성되지 않은 RESERVED_NOT_AUTHORIZED alias+entry를 전역 presentationRequests에서 각 source.reviewSignalReservations로 lossless 이동. ACTIVE만 전역 유지, 원문/enum/validator/state 불변. Agent 예약 주소 설명 및 first-slot 예시 제거. 사용자 지정 마지막 시도도 실패하여 추가 수정·유료 재시도 중단. | 11 |

### 중요한 경계와 테스트 자체의 오류

초기 실패는 하나의 이유가 아니었습니다. [초기 triage](offline-noncapacity-triage.md)는 DTO가 거절하는 한 글자 questionCode, 내부 예약 네임스페이스와 충돌하는 fixture 코드, 오래된 SELECT prompt hash 기대값을 따로 기록합니다. 당시 일부 call-count 실패는 예외를 넓게 잡는 fixture 때문에 영수증만으로 첫 원인이 노출되지 않았으므로, 전부 용량 탓이라고 단정하지 않습니다.

REPAIR-01의 [lossless identity codec](wire-identity-codec.md) 및 schema 압축은 원 USER 문장이나 모델 의미를 바꾸는 작업이 아니었습니다. 그때의 소규모 codec 통과만으로 전체 제품 통과나 기존 12,000 입력 상한 충족을 주장하지 않았습니다. 이후 32,000, 48,000 입력 상한 변경은 별도의 사용자 승인을 거친 수리입니다. 48,000 / 196,608 bytes 최종 경계와 실제 SDK wire 측정, 정정 receipt 안정성 등은 [REPAIR-03 검증](final-repair-verification.md)에 있습니다. 상한 경계 mock 값이나 UNIT 장기 fixture 수치를 대표적인 실제 사용자 요청량으로 해석하지 않습니다.

REPAIR-04는 public-history의 실제 질문만 expression target으로 증명했습니다. 근거 사실이 없는 goal을 VALID로 승격시키지 않았고 일반 CONTINUE/REVISIT 가드를 유지했습니다. 옵션 route 생략은 보존된 실제 metadata에만 기대어 복원하며 명시적 충돌은 거절했습니다.

REPAIR-05–07에서는 기존 Writer 형식 정책을 schema/local/parser-repair/render에 맞췄고, 실제 U+061F 공백을 유한 Unicode 집합으로 닫았습니다. 의미를 정규식으로 “맞게” 만들거나 실제 출력 문장을 치환한 수리가 아닙니다. 기존 길이/중복 복구 allowlist 및 혼합 결함 거절은 유지했습니다.

시스템 프롬프트 원문 불변은 **전 기간의 주장으로 쓰면 틀립니다.** REPAIR-07까지 해당 수리에서 원 Writer prompt를 유지했지만, REPAIR-08부터 사용자의 명시 승인을 받아 Writer/Writer Repair prompt를 변경했습니다. REPAIR-09에서는 primary Writer prompt와 표현용 payload를 바꿨고, REPAIR-10 이후에는 Agent prompt와 SELECT 표현 배치를 수리했습니다. 각 변경 당시 무관한 phase prompt의 동일성 검사는 그 기준 snapshot에 대한 주장입니다. 마지막에 최초 prompt와 동일하다고 보고하지 않습니다.

REPAIR-09B는 새 테스트가 pre-Writer parent episode와 post-commit 새 질문 ID를 혼동한 것을 고친 **시험 전용** 수정입니다. REPAIR-10B는 complete 입력에서 일반 `write_turn`을 요구한 양성 fixture를 실제 incomplete 입력으로 고쳤고, REPAIR-10C는 음성 fixture의 occurrence를 null이 아닌 0으로 명시해 의도한 source mismatch 경로를 시험했습니다. 실패한 09/10/10B 기록을 PASS로 바꾸거나 제품 validator를 느슨하게 만든 것이 아닙니다. [09 검증](repair09-verification.md), [10 검증](repair10-verification.md)에 원 오류와 수정 범위가 남아 있습니다.

## 3. 전체 오프라인 실제 결과: 17회

함수 PASS와 subtest는 별도 수치입니다. 같은 함수의 반복 실행을 합쳐 고유 테스트 수나 품질 점수로 부풀리지 않습니다. 아래 compile은 영수증이 보고한 해당 실행의 실제 파일 수이며, 동결 manifest에 포함된 비-Python 선언 파일 수와 다릅니다. 아래 전 회차 skip/notRun은 0이고 compile error도 0입니다.

| 실제 run / 원 영수증 | 실행 함수 | PASS | FAIL | ERROR | subtest | compile PASS/실행 | 실제 상태 |
|---|---:|---:|---:|---:|---:|---:|---|
| [initial Q11](../offline/mindot_ai/results/offline-result.json) | 82 | 24 | 6 | 52 | 68 | 42/42 | OFFLINE_BLOCKED |
| [repair-01](../offline/mindot_ai/runs/repair-01/results/offline-result.json) | 99 | 67 | 4 | 28 | 71 | 45/45 | OFFLINE_BLOCKED |
| [repair-02](../offline/mindot_ai/runs/repair-02/results/offline-result.json) | 103 | 99 | 2 | 2 | 74 | 46/46 | OFFLINE_BLOCKED |
| [repair-03](../offline/mindot_ai/runs/repair-03/results/offline-result.json) | 107 | 107 | 0 | 0 | 79 | 46/46 | OFFLINE_PASSED |
| [repair-04](../offline/mindot_ai/runs/repair-04/results/offline-result.json) | 113 | 113 | 0 | 0 | 95 | 46/46 | OFFLINE_PASSED |
| [repair-05](../offline/mindot_ai/runs/repair-05/results/offline-result.json) | 120 | 120 | 0 | 0 | 146 | 47/47 | OFFLINE_PASSED |
| [repair-06](../offline/mindot_ai/runs/repair-06/results/offline-result.json) | 120 | 120 | 0 | 0 | 146 | 47/47 | OFFLINE_PASSED |
| [repair-07](../offline/mindot_ai/runs/repair-07/results/offline-result.json) | 120 | 120 | 0 | 0 | 492 | 47/47 | OFFLINE_PASSED |
| [repair-08](../offline/mindot_ai/runs/repair-08/results/offline-result.json) | 120 | 120 | 0 | 0 | 492 | 47/47 | OFFLINE_PASSED |
| [repair-09](../offline/mindot_ai/runs/repair-09/results/offline-result.json) | 120 | 119 | 1 | 0 | 492 | 47/47 | OFFLINE_BLOCKED |
| [repair-09b](../offline/mindot_ai/runs/repair-09b/results/offline-result.json) | 120 | 120 | 0 | 0 | 492 | 47/47 | OFFLINE_PASSED |
| [repair-10](../offline/mindot_ai/runs/repair-10/results/offline-result.json) | 121 | 120 | 0 | 1 | 496 | 47/47 | OFFLINE_BLOCKED |
| [repair-10b](../offline/mindot_ai/runs/repair-10b/results/offline-result.json) | 121 | 120 | 1 | 0 | 496 | 47/47 | OFFLINE_BLOCKED |
| [repair-10c](../offline/mindot_ai/runs/repair-10c/results/offline-result.json) | 121 | 121 | 0 | 0 | 496 | 47/47 | OFFLINE_PASSED |
| [repair-11](../offline/mindot_ai/runs/repair-11/results/offline-result.json) | 121 | 121 | 0 | 0 | 509 | 47/47 | OFFLINE_PASSED |
| [repair-12](../offline/mindot_ai/runs/repair-12/results/offline-result.json) | 121 | 121 | 0 | 0 | 509 | 47/47 | OFFLINE_PASSED |
| [repair-13](../offline/mindot_ai/runs/repair-13/results/offline-result.json) | 121 | 121 | 0 | 0 | 516 | 47/47 | OFFLINE_PASSED |

최종 REPAIR-13 receipt는 `sourceBytesUnchanged=true`, `sourceBytesUnchangedDuringRun=true`, `productNetworkOrSecretAttempts=0`, `paidCalls=0`를 기록합니다. 옛 영수증에 없는 필드는 기계 manifest에서 null로 남겼으며, 미관측을 자동 성공으로 바꾸지 않았습니다.

### 표적 원인 진단: 3회

전체 discovery 수가 99/103이어도 아래 실행은 선택된 하나의 진단 함수입니다. 나머지를 전체 suite의 “skipped”로 계산하지 않습니다.

| 진단 run | 선택·실행 | PASS/FAIL/ERROR | 실제 상태 |
|---|---:|---:|---|
| [repair-01-projection-diagnostic](../offline/mindot_ai/runs/repair-01-projection-diagnostic/results/offline-result.json) | 1 | 0/1/0 | TARGETED_BLOCKED |
| [repair-01-safety-diagnostic](../offline/mindot_ai/runs/repair-01-safety-diagnostic/results/offline-result.json) | 1 | 0/0/1 | TARGETED_BLOCKED |
| [repair-02-wire-diagnostic](../offline/mindot_ai/runs/repair-02-wire-diagnostic/results/offline-result.json) | 1 | 0/1/0 | TARGETED_BLOCKED |

### Adapter 검증: 17회

이 검증은 SDK fake를 통한 collector/adapter 연결과 raw/accepted-state 계약 검증입니다. 실제 유료 출력의 의미 품질 검증이 아닙니다. 원 실패 01도 보존했습니다.

| 실제 run / 원 영수증 | PASS/실행 | FAIL/ERROR | compile PASS/실행 | 실제 상태 |
|---|---:|---:|---:|---|
| [q11-adapter-01](../offline/mindot_ai/runs/q11-adapter-01/results/offline-result.json) | 6/10 | 4/0 | 45/45 | ADAPTER_BLOCKED |
| [q11-adapter-02](../offline/mindot_ai/runs/q11-adapter-02/results/offline-result.json) | 10/10 | 0/0 | 45/45 | ADAPTER_PASSED |
| [q11-adapter-final-03](../offline/mindot_ai/runs/q11-adapter-final-03/results/offline-result.json) | 10/10 | 0/0 | 46/46 | ADAPTER_PASSED |
| [q11-adapter-verified-03](../offline/mindot_ai/runs/q11-adapter-verified-03/results/offline-result.json) | 10/10 | 0/0 | 46/46 | ADAPTER_PASSED |
| [q11-adapter-verified-04](../offline/mindot_ai/runs/q11-adapter-verified-04/results/offline-result.json) | 10/10 | 0/0 | 46/46 | ADAPTER_PASSED |
| [q11-adapter-verified-05](../offline/mindot_ai/runs/q11-adapter-verified-05/results/offline-result.json) | 10/10 | 0/0 | 47/47 | ADAPTER_PASSED |
| [q11-adapter-verified-06](../offline/mindot_ai/runs/q11-adapter-verified-06/results/offline-result.json) | 10/10 | 0/0 | 47/47 | ADAPTER_PASSED |
| [q11-adapter-verified-07](../offline/mindot_ai/runs/q11-adapter-verified-07/results/offline-result.json) | 10/10 | 0/0 | 47/47 | ADAPTER_PASSED |
| [q11-adapter-verified-08](../offline/mindot_ai/runs/q11-adapter-verified-08/results/offline-result.json) | 10/10 | 0/0 | 47/47 | ADAPTER_PASSED |
| [q11-adapter-verified-09](../offline/mindot_ai/runs/q11-adapter-verified-09/results/offline-result.json) | 10/10 | 0/0 | 47/47 | ADAPTER_PASSED |
| [q11-adapter-verified-09b](../offline/mindot_ai/runs/q11-adapter-verified-09b/results/offline-result.json) | 10/10 | 0/0 | 47/47 | ADAPTER_PASSED |
| [q11-adapter-verified-10](../offline/mindot_ai/runs/q11-adapter-verified-10/results/offline-result.json) | 10/10 | 0/0 | 47/47 | ADAPTER_PASSED |
| [q11-adapter-verified-10b](../offline/mindot_ai/runs/q11-adapter-verified-10b/results/offline-result.json) | 10/10 | 0/0 | 47/47 | ADAPTER_PASSED |
| [q11-adapter-verified-10c](../offline/mindot_ai/runs/q11-adapter-verified-10c/results/offline-result.json) | 10/10 | 0/0 | 47/47 | ADAPTER_PASSED |
| [q11-adapter-verified-11](../offline/mindot_ai/runs/q11-adapter-verified-11/results/offline-result.json) | 10/10 | 0/0 | 47/47 | ADAPTER_PASSED |
| [q11-adapter-verified-12](../offline/mindot_ai/runs/q11-adapter-verified-12/results/offline-result.json) | 10/10 | 0/0 | 47/47 | ADAPTER_PASSED |
| [q11-adapter-verified-13](../offline/mindot_ai/runs/q11-adapter-verified-13/results/offline-result.json) | 10/10 | 0/0 | 47/47 | ADAPTER_PASSED |

## 4. 실제 유료 canary 전체: 11차례 dispatch

모든 시도는 최종 전체 상태가 CANARY_BLOCKED입니다. 표의 PASS/FAIL은 그 시도의 개발 canary review이며 blind 공식 채점이 아닙니다. “시도/미실행”은 계획된 9건 중 실제 착수한 수와 남은 수입니다. 생성/Moderation 수는 dispatch attempts로서, 다섯 번째 UNKNOWN 전송이 provider에서 실행됐다고 확정하는 수치가 아닙니다.

| 감사 번호 / 실행명 | 동결 source | 시도/미실행 | 생성/Moderation | 확정 생성 tokens | 실제 결과와 다음 수리의 계기 |
|---|---|---:|---:|---:|---|
| [1 / 최초](canary-attempt-01-finalization.json) | REPAIR-03 | 1/8 | 1/1 | 12,718 | 첫 사례가 public-history presentation 목표 결속에서 GOAL_BINDING_UNRECOVERABLE_presentation. Writer 미도달. 최초 runner의 canonical finish가 없어 사후 감사가 derived CANARY_BLOCKED로 분리 기록. |
| [2 / retry01](canary-attempt-02-finalization.json) | REPAIR-04 | 1/8 | 3/1 | 17,431 | 목표 결속은 통과, phantom alias는 허용된 ARGUMENT_REPAIR로 복구. Writer option들이 질문 형식이라 writer_nonrepairable_shape. |
| [3 / retry02](canary-attempt-03-finalization.json) | REPAIR-05 | 1/8 | 3/1 | 17,806 | 형식 검사는 통과하여 실제 CONTINUE/commit까지 갔지만 가상 예시 답변/보기 선택 의미 불충족으로 개발 semantic FAIL. |
| [4 / retry03](canary-attempt-04-finalization.json) | REPAIR-06 | 1/8 | 3/1 | 18,819 | Arabic U+061F 질문형 보기와 원 탐색 질문 반복. 실제 CONTINUE/commit 뒤 semantic FAIL. 당시 validator의 형식 공백은 후속 REPAIR07에서 수정. |
| [5 / retry04](canary-attempt-05-finalization.json) | REPAIR-08 | 1/8 | 1/1 | UNKNOWN (예약 18,806) | Moderation와 SELECT 모두 APIConnectionError; raw/model response 없음. 실행·사용량 UNKNOWN이며 18,806 예약을 유지. 의미 평가 미실시. |
| [6 / retry05](canary-attempt-06-finalization.json) | REPAIR-08 | 1/8 | 3/1 | 19,215 | 같은 REPAIR08 소스·runtime으로 네트워크 실행환경만 복구. 실제 Writer가 사용자 사실처럼 서술하고 원 질문 반복하여 semantic FAIL. |
| [7 / retry06](canary-attempt-07-finalization.json) | REPAIR-09B | 2/7 | 4/2 | 40,493 | example-only PASS. 두 번째에서 잘못된 source 구절/실질 내용의 help 오분류, CONTENT 모두 비어 invalid_occurrence; Writer/commit 없음. |
| [8 / retry07](canary-attempt-08-finalization.json) | REPAIR-10C | 2/7 | 3/2 | 43,205 | example-only PASS. 두 번째 latest 도움 절이 slot000/첫 target에 들어가고 CONTENT 모두 비어 invalid_occurrence. |
| [9 / retry08](canary-attempt-09-finalization.json) | REPAIR-11 | 2/7 | 3/2 | 44,323 | example-only PASS. 두 번째 네 CONTENT 제안은 맞았지만 help source/target이 첫 slot/질문으로 잘못 결속. 전체 batch reject. |
| [10 / retry09](canary-attempt-10-finalization.json) | REPAIR-12 | 2/7 | 3/2 | 44,543 | example-only PASS. 두 번째 네 CONTENT 제안은 맞았지만 help slot/target 오류와 nonexistent signal1 alias가 남음. 순서 변경으로 해결되지 않음. |
| [11 / retry10](canary-attempt-11-finalization.json) | REPAIR-13 | 2/7 | 4/2 | 46,802 | example-only PASS(허용된 ARGUMENT_REPAIR 후 Writer). 두 번째는 CONTENT 네 배열 모두 비고 ACK 도움 절은 slot000/FOR1, presentation target/alias는 ACK/slot003으로 불일치. invalid_occurrence, Writer/commit 없음. 최종 중단. |

최초 시도는 runner의 engine canonical finish가 없어서 보존 증거를 바탕으로 별도 감사가 `derived CANARY_BLOCKED`를 작성했습니다. 이를 실제 engine finish가 있었다고 소급하지 않습니다. 이후 canonical 결과와 감사는 각각의 원 파일로 보존했습니다.

REPAIR-07 자체로 paid를 실행한 적은 없습니다. REPAIR-09, REPAIR-10, REPAIR-10B의 실패한 fixture suite 상태에서 paid로 넘어가지 않았고, 바로잡은 09B/10C가 쓰였습니다. 다섯 번째 전송 실패 후 여섯 번째 시도는 REPAIR-08 제품과 frozen runtime을 그대로 사용한 네트워크 실행 환경 복구였습니다. 모델 응답이 없는 전송 실패를 semantic FAIL로 바꾸거나 “실제 비용 0”으로 처리하지 않았습니다.

세 번째·네 번째 등의 Writer 의미 실패는 실제 public CONTINUE/commit까지 진행한 경우가 있습니다. 사후 개발 review가 FAIL이라고 해서 당시 accepted-state를 없었던 것으로 보고하지 않습니다. 반대로 아홉 번째·열 번째 시도에서 네 CONTENT 제안이 개선됐어도 같은 batch의 잘못된 signal 때문에 전량 거절됐습니다. 그러므로 그것은 **모델이 제안한 내용**이지 accepted evidence나 성공한 완료 기록이 아닙니다.

## 5. 마지막 REPAIR-13 실제 결과와 남은 결함

첫 `example-only`는 SELECT → 허용된 ARGUMENT_REPAIR → Writer로 진행했습니다. SELECT가 생성하지 않은 두 번째 alias를 선택했으나 실제 repair 응답이 존재하는 alias 하나로 줄였습니다. Writer의 명시적 가상 A/B와 가까움/둘 다 아님 질문, atoms 0·coverage NOT_EXPLORED를 확인해 PASS로 기록했습니다. 예시 다양성 제한을 별도 caveat로 남겼습니다. 이 한 케이스 PASS를 전체 모델 합격으로 확대하지 않습니다.

두 번째 `complete-substantive-example`에서는 다음이 실제로 남았습니다.

- 네 CONTENT 배열이 모두 비었습니다.
- 최신 도움 구절은 slot_003/ACK_4 원문인데 signal은 slot_000/FOR_1에 놓였습니다.
- presentation target은 ACK_4, signal target은 FOR_1로 서로 달랐습니다.
- `requestIds`의 slot_003:signal_0/1은 입력에 예약 주소로 있었을 뿐 실제 signal 출력으로 생성되지 않았습니다.
- `answer_review_batch_rejected:invalid_occurrence`로 전량 거절됐습니다. Writer 호출·public response·accepted state는 없습니다.

이 오류의 관측 위치는 확정되지만, 모델이 왜 첫 slot을 골랐는지의 내부 원인은 관측할 수 없습니다. schema source 설명, source-local annotation, CONTENT-first 순서, 예약 주소의 source-local 이동은 일반적 표현 개선 가설에 따른 수리였고, 마지막 결과가 그 해결을 증명하지 못했습니다. 출력의 source/target을 서버가 자동 바꾸거나 정답으로 간주해 validator를 통과시키지 않았습니다.

마지막 두 케이스의 확정 생성 사용량은 각각 20,421과 26,381, 합계 46,802 tokens입니다. 첫 케이스의 3회와 두 번째의 1회, 총 생성 4회·Moderation 2회가 있었고, 이후 호출은 하지 않았습니다. 최종 감사는 journal 91개 체인, lock 608/608, REPAIR-13 source 50/50, 재사용 frozen-runner-retry04 50/50 일치를 보고합니다. 작업자 exit 0은 정상 종료 관측이지 canary 합격이 아닙니다.

최종 증거:

- [canonical 결과](../live/mindot_ai/canary-retry10/canary-result.json): SHA-256 `c2793aec0914874de03a49307ceb2281be3caa9eaa76dd3eff251d78f0e2b550`.
- [독립 감사 JSON](canary-attempt-11-finalization.json): SHA-256 `a6c2a73dad200327bea88bfe5205400bc62d335a6c8aeb89efe064ff48b3bae7`, 38,535 bytes.
- [REPAIR-13 오프라인 결과](../offline/mindot_ai/runs/repair-13/results/offline-result.json): SHA-256 `384ba6e3a1325cc8524b4026e37fed731627a1a59b963377d2df29eeb24d07d1`.

## 6. 비용·공식 평가·종료의 진실 범위

누적 생성 dispatch 31회, Moderation dispatch 16회입니다. provider 응답에서 확정된 생성 입력 300,894 + 출력 4,461 = **305,355 tokens**입니다. cached prompt tokens는 이 합계 안에 이미 포함되며 Moderation은 생성 token 합계 밖입니다.

다섯 번째 전송 실패의 actual execution/usage는 **UNKNOWN**, actual tokens는 null로 유지됩니다. 예약 18,806을 더한 **324,161은 회계상 확정+예약 합계일 뿐 실제 사용량이나 청구서 금액이 아닙니다.** 따라서 전체 actual usage를 305,355 또는 324,161로 확정할 수 없습니다.

공식 수집·채점은 **0/368, NOT_RUN**입니다. Q10 대 Q11 승자, blind 성적, canonical grade lock 또는 공식 전량 평가 완료를 만들지 않았습니다. hidden 평문이나 rubric을 이 이력 작성에 사용하지 않았습니다. 마지막 사용자 지시에 따른 제품 수정·추가 paid retry 중단과 기존 실패·raw·lock·claim 보존은 그대로 유지됩니다. ZIP/Drive 전달/PC 종료는 부모 작업이며 본 문서가 실행했다고 주장하지 않습니다.

## 부록: source-set 식별자

각 값은 해당 receipt의 원 source-set 정의입니다. 개별 파일 SHA 및 직전 동결본과의 전체 변경 목록은 [기계 manifest](repair-history-manifest.json)에 있습니다.

| 동결본 | 원 source-set SHA-256 |
|---|---|
| Q11 | `cffc49083503b1621f3742094d6055efd9e8d05234bb6711c7af75871066488d` |
| REPAIR-01 | `0b904b04ae7f4d58498fdd8f3d5ea7747dd423ce1a5d0e7f5b80d2a4c6f1bf39` |
| REPAIR-02 | `7d8834edd6fab59798e61cc401cf2d2e6d022a641fd19c11d306c61bd0f6acdc` |
| REPAIR-03 | `0aa23b3e392e6b183e884d9eaf6aa9f5a04b7a78a444b3d246aae6a1d770937a` |
| REPAIR-04 | `fd120ff31ca5bc0e882e4955cb16aa60687679fb35f54d4116458bd1626441f3` |
| REPAIR-05 | `3d49fb98e6557e5de6b8d746f8bf43d6cfbd8c8a3a6ef8e577c7f21200f0e2d8` |
| REPAIR-06 | `d4b9a472524f944304bee1e68227f91fcadebf0604eadb560b7545b572b933df` |
| REPAIR-07 | `88b150458f73cde14a7333461ee50d279e63b63a3e86bb8e13e2fe7fbdca868d` |
| REPAIR-08 | `02e804576f2a83fc7a1ab6a5c2e2173ac7ec48b408345adb1e52757deec4686f` |
| REPAIR-09 | `367c378c1defc6a75d57958aa483e538eac406c572cc5e3e057e05563a93c004` |
| REPAIR-09B | `2b206523eadc1740055e3eeffc52fd7c81acfb8f7163dc72f365db81556517b7` |
| REPAIR-10 | `c33fcbc1cc948b69a4ff1fe63ed6c7b6c39b6dfb4426b76a98951c6967f9a5ea` |
| REPAIR-10B | `5bfda54605183393cc7be5d9d2db2b1b1438c2b5ffc599d152f32f029020bf89` |
| REPAIR-10C | `0c6f6eb6a67353aa07fa85eb2e9a4c439445625b53e86f1534d98f8e0484897a` |
| REPAIR-11 | `78797873f09aef97add334ffb05ba28be9d7bc6174ed7cd1267a4b1f413a4b31` |
| REPAIR-12 | `7de56f098ce938dcba1886ae32e2a671c9f58da9cf1d57cd15adf001549e7abd` |
| REPAIR-13 | `f4fa31387855250597e68a04b6e09d3bcade680ad406dcbfa0d5e54c1278e927` |

