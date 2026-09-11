# Q13 테스트 계약 정합화

- 기존 scripted `assess_completion` 호출은 필수 인자인 `fallbackQuestion`이 없었다. 모든 평가 fixture가 실제 fallback 질문을 전달하도록 수정했다.
- 기존 ESTABLISHED 후보는 내부 필수 필드인 `changeStatus`가 없었다. 후보에는 `ESTABLISHED`를 추가하고 외부 `currentProposal` 비교에서는 이 내부 필드를 제외한다.
- null AFTER는 과거 `UNRESOLVED` 오류로 기대했지만 Q13 계약상 정상 `NOT_ESTABLISHED`이다. 이제 같은 호출의 fallback을 사용하는 `QUESTION/DIALOGUE`, `currentProposal=null`, `issue=null`과 2회 호출을 기대한다.
- assessment reviewer 거부도 오류가 아니라 같은 fallback을 표시하는 정상 대화 복귀이며, 3회 호출을 기대한다. 잘못된 인용과 의미 구조 위반만 `UNRESOLVED/INVALID_CANDIDATE`로 유지한다.
- malformed schema와 provider 실패는 정상 미성립으로 바꾸지 않고 기술 실패로 유지한다.
