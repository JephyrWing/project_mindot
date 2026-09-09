# Mindot Q11 CBT 범위 교정 패키지

계약 simple-dialogue-2 · canary simple-dialogue-canary-2 · 공개 세트 cbt-known-scope-2 · 2026-09-09.

Mindot-Q11-Question-First-Codex-Prompt.md가 새 대화창에서 사용할 전체 지시문이다. 이미 구현한 simple-dialogue-1 최신 제출본에서 이어서 수정한다. source와 평가 자료 위치는 asset-index에 있다.

범위 밖 초안 명령과 상충한 테스트 기대를 제거하고 일반 질문의 중복 target을 없앴다. 실제 Agent·원문 메모리·다섯 도구·Writer/Assessor·기존 사용자 확인/중단·공개 API는 유지한다. 새로운 문서 작성 기능이나 별도 의미 검사 모델은 없다.

docs/cbt-q11-simple/에 설계·schema·완성 prompt5개·canary·검증과 검토 기록이 있다. docs/cbt-q11-evaluation/에는 실행·raw 계약, 현재 known170과 원본 사본/수정 이력, Q10/holdout 신원이 있다. 현재 실행은 known/을 사용하며 known-original/은 역사 보존용이다. 문서 경로를 제품 runtime에서 import하지 않는다.

작성 규칙v1.21과 grader rubric v1.12는 작성자/채점자 자료다. 구현자는 rubric hash만 확인한다. 100점 품질 배점과368결과 수집은 유지하지만 복수의 적법 행동10개를 단일 정답 분류 진단에서 사전 구분하므로 진단 대상 수는 과거와 다르다. 과거 점수와 직접 개선율 비교하지 않는다.

현재는 전달 명세 검토이며 제품 코드 수정·제품 오프라인·새 canary·공식 평가 실행 완료가 아니다. 이전 raw와 공식0/368은 보존했다. 이번에 봉인 key/평문을 읽지 않았다.
