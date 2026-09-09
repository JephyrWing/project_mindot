# 유형 레지스트리
첨부 12개 code/nameKo/description은 기존 공개 입력과 현재 AI enum의 기준이다. 임상 지식·새 라벨을 덧붙이지 않는다. 실행 소스에서 같은 code와 정의를 확인하고 하나의 원본으로 공급한다. legacy cbt_agent.py를 DTO/enum 때문에 import하다 옛 정책 엔진이 실행되지 않도록 필요한 공용 모델을 분리할 수 있다. 제품 prompt의 코드 나열과 schema/대화에 전체 정의를 반복 복사하지 않는다. 테스트의 expected label과 이 레지스트리는 다르며 expected를 제품에 넣지 않는다.
