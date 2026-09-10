# Mindot CBT 실행 패키지

현재 계약은 **simple-dialogue-1 (2026-09-09)** 이다. 이 패키지는 Codex가 이번 재설계를 처음 받는 상황을 위한 통합본이다. 앞서 ChatGPT가 작성한 question-first-1 및 단순화 초안은 아직 Codex에 전달되지 않았다. 파일의 고정 이름은 작성 중 갱신한 문서의 이름일 뿐, 이전 지시의 전달·구현 완료를 뜻하지 않는다.

마지막 중단 상태(REPAIR-13)와 현재 실제 worktree에서 이 계약을 바로 구현한다. 이전 설계를 먼저 적용하는 중간 단계는 없다. 필요한 완성 prompt5개·schema·설계·검증/평가 지시가 모두 포함돼 있으며, 기준 코드와 평가 원본의 정식 위치는 asset-index에 명시돼 있다.

`Mindot-Q11-Question-First-Codex-Prompt.md` 전문을 Codex에 붙여넣고 이 ZIP을 함께 제공한다. 실행 명세와 완성된 모델 prompt·schema·canary는 docs 아래에 있다. schema.py는 작성자가 제공한 reference이며 제품은 docs를 runtime import하지 않는다. 제품 내부로 옮겨 실제 SDK 호환과 통합 경로를 검증한다.

일반 질문은 Agent→Writer, 완료는 Agent→Assessor→같은 Agent의 검토다. 원문·정정·요청·중단/재개·안전 기본 대응을 보존하고, 전원 답변 분류와 네 영역 완료조건·과도한 문체 검증을 제거한다. canary는 두 실제 연속 대화를 우선한다.

조건을 통과하면 유료 평가까지 재승인 없이 진행한다. 이번 패키지 작성 과정에서 제품 구현·오프라인 제품 테스트·유료 API·canary·공식 평가를 실행하지 않았다. 새 candidate source hash는 구현 후 잠근다. 과거 공식 상태0/368을 완료로 바꾸지 않는다.

rubric v1.11은 별도 grader 문서다. 평가 입력·배점·산식은 보존하되 네 영역과 평가 준비도를 분리하는 해석을 새로 적용한다. 구현자는 asset-index의 byte/hash만 잠그며 전문을 읽거나 점수를 매기지 않는다. known/hidden 기대값은 그대로 두고 새 기준과의 충돌을 결과에서 별도 해석한다.

manifest.json은 포함 파일의 bytes/SHA-256을 제공한다. 암호화 holdout만 포함하며 release key와 평문은 포함하지 않는다. 원래 known 파일과 Q10 source는 asset-index의 정식 경로에서 확보한다. 실제 제품 Python 전체는 Codex 실행 결과 full/audit ZIP에 동봉한다.


입력 용량 정책 갱신: capacity-policy-1. 입력48,000 tokens/192 KiB는 정식 기본값이며 정상 요청에 부족하면 첫 canary 전 측정 기반으로 조정·잠금한다. 제품 prompt 글자 수 제한과는 별개다. 실제 총량·호출 수는 늘리지 않았고 rubric v1.11과 사례/기대값도 바꾸지 않았다.

48k는 저장된 큰 요청36,238에30% 여유를 적용해 재검토한 기본값이다. 상세는 `docs/cbt-q11-simple/input-capacity-decision.md`. 새 제품 전체 요청/실제 모델의 검증 완료를 뜻하지 않는다.
