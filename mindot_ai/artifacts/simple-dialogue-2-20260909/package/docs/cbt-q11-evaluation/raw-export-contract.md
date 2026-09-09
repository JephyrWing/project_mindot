# 구현 중립 raw export 계약

이 문서는 rubric v1.12의 실행/자료 경계만 옮긴 실행 입력이다. 점수·가중치·최종 품질 판단을 포함하지 않는다. 새 비교에만 적용하며 과거 raw/grade를 수정하지 않는다.

## 1. Canonical payload

case×anonymous version을 같은 schema로 정규화한다. 내부 구조가 없다는 이유로 baseline에 새 의미 분석을 실행하거나 role/evidence를 추정하지 않는다. 모든 행은 같은 key 집합·nesting을 가지며 불명은 공통 null/UNKNOWN, 비해당 collection은 같은 빈 배열 규칙을 쓴다. scalar 부재와 해당 의미 없음은 구별한다. wrapper의 구현별 branch 이름을 blind로 복사하지 않는다.

필수 의미 그룹:

- identity: opaque case_id, response_id, anonymous_version, 공통 suite/case type와 synthetic 입력 context.
- input: situation_family, vulnerability_type, automaticThought, latestInteraction, fixed latestUserIntentHint(있으면), previousQuestions, questionAnswers, expectedDecision/Assessment/Distortions, allowed/forbiddenQuestionMeanings, 공통 semanticRouteDefinitions. hint/expected는 grading context이며 제품 입력에서 제외한다.
- accepted state: answerDisposition, blockedRoutes/blockedRouteFamilies/blockedSemanticDimensions, unansweredQuestionAttempts, resolvedButIrrelevantTopics, confirmationAllowed/completionAssessmentAllowed, coverageStateRole, coverageReviewRequired, incompleteCoverageDomains, priorVerifiedCoverage/inputExplorationCoverage, completionCandidates/completionCandidateCoverage, coverageReview, acceptedCoverage, acceptedEvidenceAtoms.
- boundary: supplementalBoundaryEvidence, effectiveSemanticMove, effectiveQuestionPlan, terminalAssessment, assessmentBoundary, factBoundaryGap, factBoundaryQuestionCount, consideredCandidates, missingDefinitionElements.
- final: renderedOutput, actualDecision, actualAssessment, actualDistortions, questionPurpose, semanticRouteType, questionGoal, answerTarget, answerSource, prefaceGoal, exampleOptions, groundingQuestionCodes, avoidTopics, finalResponse, confirmation details.
- safety: 공통 safetyCandidates/safetyReview/safetyEvidence/safetyReason. 원문 주체·현재성·원발화·해소·재개를 의미상 제공된 범위만 정규화한다. provider/episode 내부 label을 새 근거로 추론하지 않는다.
- dialogueControl: 아래의 같은 구조를 **모든 행**에 둔다.

```json
{"kind":"NONE","targetQuestionRef":null,"availableResponses":[]}
```

kind는 ANSWER_WAIT/USER_STOP/SUMMARY_ONLY/NONE/UNKNOWN. USER_STOP은 실제 사용자 중단에 대한 기존 CONTINUE 제어 안내, SUMMARY_ONLY는 판정하지 못했음을 밝힌 기존 CONTINUE 제어 안내다. 새 공개 status를 만들지 않는다. 실제 기존 답변 대기라면 targetQuestionRef는 익명 질문 참조, availableResponses는 실제 제공한 ANSWER/SKIP 선택지다. 해당 제어가 없으면 NONE, 내부 정보만으로 판단할 수 없으면 UNKNOWN과 null/빈 배열을 사용한다. renderer/tool 내부 이름을 label로 쓰지 않는다. factBoundaryGap.answerState를 내보낼 때는 양쪽 모두 NOT_ASKED/AWAITING_ANSWER/ANSWERED/UNKNOWN 또는 공통 null 규칙을 사용한다.

상태·reference·plan의 nested object schema와 null 규칙을 첫 canary 전에 실제 두 구현에 대해 작성·검증·잠근다. 허용된 가변 길이 list의 길이는 같게 만들지 않으나 element schema는 같게 한다. 서로 다른 내부 atom 개수 자체로 구조를 노출하지 않도록 같은 출처 단위로 표현할 수 있는지 확인하되 추정 병합/의미 생성은 금지한다.

## 2. 행동과 식별자 정규화

actualDecision은 QUESTION/DISTORTION_PRESENT/NO_CLEAR_DISTORTION/SAFETY_CLARIFICATION/SAFETY_STOP 및 사전 공통으로 정한 STOP/TECHNICAL_NO_OUTPUT/UNKNOWN 등 실제 외부 행동만 쓴다. 적법한 기존 답변 대기는 QUESTION+dialogueControl.ANSWER_WAIT로 나타내며 유효성 판단을 exporter가 단정하지 않는다. 실제 답변 대기인 경우에만 그 턴의 행동 class는 UNCERTAIN이며 자동사고의 독립 왜곡 label과 분리한다. 무응답·기술 오류를 정상 ANSWER_WAIT나 UNCERTAIN으로 바꾸지 않는다.

공통 source/질문/답변 참조는 의미 관계를 보존하는 불투명 ID로 매핑한다. 원래 case ID의 Q9/Q10/Q11 같은 접두사, public questionCode에 든 checkpoint/prompt/revision 문자열, 실제 구현별 ref prefix도 blind에서 제거한다. 같은 payload 안의 reference mapping은 일관돼야 한다. 정확한 USER/최종 자연어 원문은 고치지 않는다. 원본 ID·주소·실행 ID와 익명 참조의 대응은 full에 둔다.

## 3. Full 전용 사실

실제 version/branch/head/source path/hash, model/prompt version, 실제 component/tool/phase, raw/parsed 응답, prior/proposed/accepted delta, semantic analysis/frontier/replace edge, receipt/latch/episode/transaction/cache, actual provider kwargs·response schema, Moderation provenance, token/latency/call/retry/fallback/validation/outcome은 full 전용이다. API credential/Authorization/header secret은 full에도 넣지 않는다.

모든 실제 범용 invocation attempt·provider response·tool/후속검토/복구·Moderation을 집계한다. 수신하지 못한 output과 미호출을 구분한다. fake의 usage를 실제 token 비용에 합치지 않는다. 품질 점수와 critical failure 최종 판정·승자·평균은 만들지 않는다.

## 4. 수집·무결성

첫 출력 뒤 frozen expected/schema/normalization/mapping/runner를 변경하지 않는다. 출력 수신 직후 journal에 append하고 accepted response/state의 commit 결과를 별도 남긴다. complete/failed/partial/not-run은 관찰 상태다. 예정 case를 삭제해 수집률을 높이지 않는다.

blind payload의 canonical JSON UTF-8 SHA-256을 full 행에도 그대로 넣는다. `case_id + anonymous_version + response_id` 및 long-session turn index의 유일성과 양쪽 key 집합·payload hash를 검증한다. 공식 최상위 result는184×2=368이며 long 내부 turn record 수는 별도다. technical attempt 원본은 full에 전부 보존하고 최상위 공식 행과 연결한다.

누출 검사는 schema field·nested shape·실제 version/model/component strings·파일명·ID prefix와 mapping을 대상으로 한다. 자연어 내용 자체가 구현 스타일을 드러낸다는 이유로 출력 문장을 바꾸지 않는다. leakage/누락/동일 payload 불일치는 PACKAGE_INCOMPLETE로 보존한다. 단순 metadata 제거·manifest 보완은 원본 raw/기존 잠긴 의미 mapping을 보존한 재패키징으로 가능하며 제품 재호출은 없다. 의미 정규화 결함은 같은 실행의 기준을 바꿔 감추지 않는다.

GPT는 익명 blind만 먼저 읽고 채점·grade hash를 잠근 뒤 full을 연다. full/개발 보고를 먼저 열어놓고 blind-first 평가였다고 하지 않는다. 기존 개발 canary의 known 입력·출력과 새 공식 익명 대응 관계를 구별한다.

## 5. 대화 중심 구현과 기존 구현의 공통 해석

- 위 accepted state 그룹은 공통 export key 목록이다. 제품에서 매 턴 생성해야 하는 데이터 목록이 아니다. 존재하지 않는 coverage/review/atom은 null 또는 UNKNOWN으로 정규화하며 원문에서 모델을 다시 실행해 채우지 않는다. NOT_EXPLORED/EXPLICITLY_NONE으로 추정하지 않는다. 비해당 배열 []와 미측정 null을 구분한 동일 schema를 양쪽에 사용한다.
- 질문과 사용자 원문·실제 공개 결과가 채점의 중심이다. final evidence와 계획은 실제 존재할 때만 내보낸다. 원문 전체의 대화 순서, 수정 발화와 대상, 요청과 실제 응답을 확인할 수 있어야 한다.
- stopped/unresolved의 실제 제어 안내는 actualDecision=STOP, actualAssessment=null, actualDistortions=[]이며 dialogueControl.kind로 USER_STOP/SUMMARY_ONLY를 구분한다. 이는 공개 status=CONTINUE를 보존한 내부 export label이다. 판단 없는 중단을 NO_CLEAR/UNCERTAIN/정상 완료로 채우지 않는다.
- exampleOptions는 실제 공개 예시를 별도 구조로 저장한 경우만 제공한다. message만 생성한 버전에는 null; exporter가 의미 추출을 새로 하지 않는다. renderedOutput에는 실제 예시·질문 전체가 있다. 예시 개수를 맞추거나 사용자 답변처럼 만들지 않는다.
- 내부 coverage 유무에서 버전을 추론할 가능성을 완전히 제거했다고 주장하지 않는다. 양쪽 동일 key/null 규칙은 직접 metadata 누출을 줄이는 조치이고 구조적 결측의 식별 가능성은 full 감사의 한계로 기록한다. 원문과 실제 근거를 숨겨 완전 blind인 듯 만들지 않는다.
- 원본 frozen expected는 그대로 둔다. 새 대화 기준과 과거 label의 차이는 채점자가 LEGACY_EXPECTATION_CONFLICT로 별도 해석하며 Codex가 기대값·분모를 고치지 않는다. 정규화 규칙은 첫 canary 전에 양쪽 adapter에 고정한다.

## 현재 revision의 수집 구분

simple-dialogue-2와 cbt-known-scope-2를 따른다. 실제 public status·원문·응답은 보존한다. 실행 상태 RESPONSE_COMMITTED/NO_RESPONSE/NOT_RUN과 기대 결과 PASS/FAIL/UNRESOLVED/TEST_INVALID/BLOCKED_BY_PARENT/NOT_APPLICABLE_PARENT_CONFIRMATION을 구분한다. 이는 수집 metadata이며 새 제품 enum이나 DB 완료를 뜻하지 않는다.

known revision/hash와 사전 MULTIPLE_VALID_ACTIONS 목록은 평가 sidecar에만 기록한다. 모델에 기대값을 넣지 않는다. 10개도 품질·응답률·368수집에 포함한다. 단일 정답 분류 진단 구분은 GPT가 rubric v1.12로 처리하고 Codex는 점수를 만들지 않는다. 원본 contradictory expected를 현재 gold처럼 중복 전달하지 않는다.
