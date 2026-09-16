# 유형 레지스트리
첨부 12개 code/nameKo/description은 기존 공개 입력과 현재 AI enum의 기준이다. 임상 지식·새 라벨을 덧붙이지 않는다. runtime `cbt_session_agent/distortion-definitions.json`을 원본으로 공급하며 SELECT에는 정의를 넣지 않고 Assessor와 assessment-review에만 전체 정의를 제공한다. 삭제된 legacy `cbt_agent.py`, `cbt_q5`, `cbt_q11`은 활성 소스에 포함되지 않는다. 테스트의 expected label과 이 레지스트리는 다르며 expected를 제품에 넣지 않는다.
