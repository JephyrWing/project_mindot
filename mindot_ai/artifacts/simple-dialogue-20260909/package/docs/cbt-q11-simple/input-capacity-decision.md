# 입력 상한 검토 — 2026-09-09

결정: 현재 Q11의 **호출당 로컬 추정 입력48,000 tokens / 직렬화192 KiB(196,608 bytes)**를 정식 기본값으로 유지한다. 과거12,000/96 KiB로 되돌리지 않는다. 32,000으로 낮추거나64,000으로 높일 측정 근거는 현재 부족하다. 새 구조의 최적값이나 무제한 대화 지원을 입증한 것은 아니다.

## 확인한 크기

| 자료 | 로컬 추정 입력 | 직렬화 bytes | 증거 범위 |
| --- | ---: | ---: | --- |
| REPAIR-01, 24개 답변 이력+새 USER | 36,238 | 139,404 | 저장·복원 경로를 사용한 합성 UNIT 용량 fixture. 실제 생성된 성공 대화 아님. |
| REPAIR-01, 12개 답변+8회 정정+새 USER | 34,908 | 133,436 | 같은 UNIT fixture. |
| REPAIR-13 example-only SELECT | 12,498 | — | 실제 API 호출. provider prompt_tokens14,769. |
| REPAIR-13 complete-substantive-example SELECT | 16,583 | — | 실제 API 호출. provider prompt_tokens26,149. 이 호출의 기능 실패는 용량 초과가 아니었다. |
| 새 simple-dialogue-1 SELECT tools만 | 3,998 | 15,779 | 정적 schema 산술 측정. 전체 SDK 요청이나 provider 사용량 아님. |

로컬 추정은 기존 compact UTF-8 SDK kwargs에 o200k_base를 적용하고64 framing reserve를 더한 값이다. tools 단독 행에는 framing64를 더하지 않는다. provider 사용량과 같은 숫자로 취급하지 않는다. 두 실제 SELECT의 provider/local 비율은 각각 약1.182,1.577이었다.

## 32k·48k·64k 판단

- 32k: 확인된 긴 UNIT 요청36,238과 정정 요청34,908이 넘는다. 새 schema가 작아졌어도 새 전체 runtime 입력이 아직 구현·실측되지 않아 이 숫자로 다시 제한할 근거가 충분하지 않다.
- 48k: 기존 최대36,238에30% 여유를 더하면47,109.4다. 48,000은 해당 최대보다 약32.46% 여유가 있는 보수적 시작 상한이다. 입력을48k까지 채우라는 목표가 아니다.
- 64k: 현재48k를 넘는 정상 대표 요청의 측정 증거가 없다. 지금 미리 증액할 이유는 없다. 필요하면 첫 canary 전 통합 입력 측정에서 판단한다.

bytes는 별도로 본다. 139,404×1.3=181,225.2로192 KiB 안에 들어간다. 96 KiB는 실제 UNIT 요청을 막았고256 KiB까지 확대할 근거는 현재 없다. 토큰과 bytes 사이에 보편적인 환산비가 있다고 가정하지 않는다.

새 고정 schema는 과거 동적 schema보다 작다. REPAIR-13의 위 두 SELECT tools는 각각7,746/8,915 tokens였다. 같은 과거 wire에서 tools만 새 것으로 바꾸면 로컬8,750/11,666 tokens가 되는 산술 비교가 가능하다. 이것은 새 prompt/view/reducer를 구현한 결과나 실제 API 성공 증거가 아니므로 기본값을 낮추는 확정 근거로 쓰지 않는다.

## 모델 한도와 추정 오차

GPT-4o mini의 공식 context는128,000 tokens, 모델 최대 출력은16,384 tokens다. 프로젝트 SELECT 출력 cap8,192는 유지한다. [OpenAI 공식 모델 문서](https://developers.openai.com/api/docs/models/gpt-4o-mini), 확인2026-09-09.

현재 실행 계약의 입력 예약 보정1.75를 적용하면 기본 상한에서48,000×1.75+8,192=92,192다. 128,000보다35,808 여유가 있다. 64k에서는120,192로 남은 여유가7,808이다. 이 계산은 관측 기반의 보수적 계획이며 provider 입력의 보장된 상한이 아니다. 기존 context 검사에서 로컬 추정만을 실제 provider usage라고 취급하지 말고 같은 예약 보정을 반영한다. 새 검사 단계·추가 모델 호출은 만들지 않는다.

## 실행 적용

정상 대표 요청의 SDK payload 크기는 필요한 기존 오프라인 통합검증에서 함께 기록한다. 기본값이 충분하면 더 작은 숫자를 찾는 최적화나 별도 capacity 시험을 추가하지 않고 canary로 진행한다. 부족하면 첫 canary 전에 실제 최대값과 통상20~30% 여유, 모델 context/출력, bytes를 근거로 설정을 조정한다. 필요한 원문 삭제나 동적 schema/alias 복잡화로 상한만 맞추지 않는다.

config·runner·canary의 effective 값과 추정법을 같은 lock에 기록한다. canary/공식 평가 중 조용히 바꾸지 않는다. 기존 승인 범위 안의 사전 측정 조정은 임시 허용을 다시 요청하지 않는다. 누적 예산과 호출/round 수는 별도이며 자동 증가하지 않는다. 현재 canary1,750,000은 실제 usage+미확인 예약의 중단 예산이고 모든 호출의 최대 크기 조합을 보장하는 금액은 아니다.

이번 작업은 저장된 기록·정적 산술·문서 정합성 검토다. 새 제품 테스트·canary·유료 API 호출은 하지 않았다. 48k가 모델의 의미/출처 판단 오류를 해결한다고 주장하지 않는다.

## 근거 위치

- 중단 raw ZIP 내 `reports/token-budget-adjustment.md` 및 `offline/mindot_ai/runs/budget-extended/capacity-probe/extended-capacity-probe.json`.
- 중단 raw ZIP 내 `live/mindot_ai/canary-retry10/example-only.json`, `complete-substantive-example.json`: 동일 phase/ordinal의 component_input과 response 결합.
- 중단 source `execution/REPAIR-13/mindot_ai/cbt_q11/llm.py`의 INPUT_TOKEN_LIMIT, REQUEST_BYTE_LIMIT, measure.
- 이 패키지의 `schema.py` / `artifact-checks.json`. 새 제품 전체 wire는 미측정.
