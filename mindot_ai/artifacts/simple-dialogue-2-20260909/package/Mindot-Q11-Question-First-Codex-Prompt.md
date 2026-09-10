# Mindot: CBT 범위에 맞춘 테스트 교정과 최소 구조 수정

이 지시문과 ZIP만으로 새 Codex 대화에서도 시작할 수 있다. 현재 계약은 simple-dialogue-2, canary는 simple-dialogue-canary-2, 공개 세트는 cbt-known-scope-2다. **이미 구현·실행된 simple-dialogue-1에서 이어서 수정한다.** 이전 대화 수신을 가정하거나 REPAIR-13부터 다시 구축하지 않는다.

기준은 Drive `1ytTNH8V1sqj4V6PlgdsDoaH1KjtrzzLY`의 `Mindot-Q11-Simple-Dialogue-Audit-20260909T043047Z.zip`, 내부 source/mindot_ai다. 파일별 신원은 implementation-base-manifest에 있다. 실제 worktree와의 차이를 먼저 보존하고 현재 cbt_simple 경로를 수정한다. 제출 당시 canary는 중단됐고 공식 결과는0/368이다. 이 명세 검토가 새 제품의 테스트 통과를 뜻하지 않는다.

## 목표와 권한

최우선은 맥락에 맞고 사용자가 답할 수 있는 CBT 질문이다. 기존 CBT 기능만 제공한다. 테스트의 “초안을 보여 달라”는 명령 때문에 범용 문서 작성·명령형 초안 생성·강제 완료 기능을 추가하지 않는다. 기존 Assessor 판단과 사용자 확인용 outcomeDraft는 자료와 대화에 근거해 선택한다.

mindot_ai 안의 구현·자체 리뷰·필요 오프라인·한 round canary·통과 시 공식 유료 평가368결과와 raw 제출까지 진행한다. 중간 재승인을 만들지 않는다. back/front는 읽기 전용이다. 기존 변경을 reset/clean/restore하지 않는다. 실제 LangGraph Agent·전체 원문 메모리·Writer/Assessor callable·같은 Agent 최종 검토·공개 URL/DTO·멱등성·취소·원자적 저장을 유지한다. git commit/push/배포/PC 종료·사람에게 메시지 전송은 포함하지 않는다.

## 현재 명세

README와 manifest를 확인하고 다음 순서로 읽어라.

1. docs/cbt-q11-simple/product-scope-and-test-policy.md와 design-and-contract.md
2. 같은 폴더의 schema.py와 완성 prompts/5개
3. offline-checks.md, canary-plan.json, package-review.md, input-capacity-decision.md
4. docs/cbt-q11-evaluation/execution-contract.md, known-revision-contract.md, known-revision-manifest.json, raw-export-contract.md, asset-index.json

시작 시 Drive iteration log `1SEXzKZPNTJzuzjrcjp8hiyBnMHU4oetu`를 읽기 전용으로 확인한다. 접근 불가면 동봉 snapshot과 최신성 한계를 기록하고 영향 없는 작업을 계속한다. 과거 로그가 현재 지시를 덮어쓰지 않는다. 작성자 규칙과 grader rubric 본문으로 구현을 튜닝하지 않는다. rubric은 bytes/hash만 잠그며 Codex는 점수를 매기지 않는다.

## 구현할 변경

- 일반 write_turn에서 targetQuestionCode를 제거하고 updates/move/focus/sourceIds만 받는다. 일반 GOAL 상태는 실행 허가 조건이 아니다. NORMAL의 purpose/route는 선택한 move에서만 가져오고 서버가 이전 질문을 target으로 자동 삽입하지 않는다.
- 예시·설명·WAIT의 실제 대상 questionCode와 gapId/episodeId, 정정 대상은 유지한다. 일반 GOAL은 선택적 메모이며 실제 gap 답변·SKIP·철회 후 대기 효력은 보존한다. 원문 존재만으로 답변 완료를 서버가 자동 판정하지 않는다.
- 저장 원문은 유지하고 이번 발화를 실제 sourceId와 함께 마지막 HumanMessage로 직접 제공한다. 다른 context에 같은 text를 중복하지 않는다. START·cold·과거 답변 수정·여러 변경과 다음 phase의 원문 접근을 명세대로 처리한다. S번호 크기로 최신 발화를 추측하지 않는다. 실제 문답 참조와 미답변 pendingQuestion을 구분한다.
- conversation에는 공개 질문과 답변 연결을 제공하며 과거 내부 focus/plan을 반복하지 않는다. Agent/Writer 완성 문안은 구체적 질문 목표, 현재 정정과 기존 중단 의사를 처리하도록 교체한다. 새 의미 검사기·분류표·정규식 반복 검사·보조 LLM·도구는 추가하지 않는다.
- prompt5개와 schema를 그대로 적용한다. 일반 target 제거를 다른 대상 필드 일괄 삭제로 확대하지 않는다. 실패 사례의 정답·예문을 프롬프트에 덧붙이지 않는다.
- 입력 정식 기본값48,000 추정 tokens/196,608 bytes를 유지한다. 이번 관측에서 용량 실패가 없어 별도 축소/확대 최적화는 하지 않는다. 정상 대표 요청이 부족하다고 측정되면 첫 canary 전 기존 정책으로 모델 한도·출력·승인 총량 안에서 조정하고 함께 잠근다. 임시 허용을 다시 묻지 않는다.
- 일반 생성2회/완료3회, 턴당 최대3·Moderation1·SDK retry0을 유지한다. 실제 Writer 형식 오류만 같은 plan으로 제한1회 복구한다.

## 검증과 제출

기존 기능 검사를 재사용해 변경의 연결만 오프라인 검증한다. 새 테스트 개수·검토 round 목표를 만들지 않는다. source/prompt/schema/runner/개정 입력·기대를 함께 잠근 뒤 최대12요청 canary 한 round를 실행한다. 사례마다 수정·재시작하지 않고 독립 사례를 끝까지 수집한다.

정당한 부모 확인 결과 때문에 종속 질문이 없으면 NOT_APPLICABLE_PARENT_CONFIRMATION과 미관측 기능으로 기록한다. 가짜 부모를 넣거나 고정 턴 수를 채우려고 제품을 계속 질문하게 하지 않는다. 부당한 조기판정·실패 부모는 이 예외가 아니다. commit된 응답의 기대 status 불일치를 기술적 무응답으로 기록하지 않는다. 테스트 결함은 TEST_INVALID로 구분한다.

공개 공식 세트는 ZIP known/의 개정본을 사용한다. 기존 Drive known이나 known-original/에서 옛 기대를 다시 가져오지 않는다. 10개 복수 허용 행동 사례의 nullable expected/allowedDecisions를 known-revision-contract대로 처리한다. 기존 signature/coverage gate를 새 이름으로 재생성하지 않는다. 양쪽에 같은 자료를 적용하고 평가 metadata는 제품 입력에서 제외한다.

적용 가능한 canary 필수 기능과 실행 무결성이 통과하면 formal lock → 기존 승인 조건에 따른 봉인 공개 → Q10/Q11 각184, 총368결과 수집 → blind/full Drive 제출까지 재승인 없이 계속한다. key/평문은 formal gate 전 열지 않는다. 봉인 원본은 바꾸지 않는다. 공식 실행 중 source·입력·기대·runner·rubric을 고치거나 좋은 답만 재생성하지 않는다.

실제 접근/기록/예산/lock 장애나 canary blocker가 남으면 모든 확보 raw와 원인·미실행 범위를 제출한다. 무제한 유료 round 반복은 승인되지 않았다. ZIP에는 실행한 관련 Python 전체·tests/의존성·diff·manifest·실제 입출력·usage를 포함한다. 결과 Drive 폴더는 `1kQ8MriKCP1afqw7jpLFlHzaKAPuDGmgW`다.

최종 보고는 실제 변경, 오프라인/실제 모델 실행 수, canary 결과와 미관측 범위, 공식 수집 수, usage·source hash·제출 링크를 간결히 쓴다. 품질 점수·버전 우열·XLSX는 GPT가 blind-first로 처리한다.
