# 구현 연결 메모

기준 소스의 실제 경로는 `app.py → cbt_session_agent package → cbt_session_agent.py facade → service.py → graph.py`다. 삭제된 legacy 구현은 활성 경로에 존재하지 않으며 평가용 동결 사본만 `mindot_ai/artifacts`에 남는다. 아래는 과잉 의미 검증을 추가하기 위한 표가 아니라 기존 사용자 기능의 생산자·소비자 연결이다.

| 변경 대상 | 끝까지 연결할 곳 |
|---|---|
| time-ordered messages | 기존 question_answers 읽기, 미답변 assistant/저장된 user, AI hydration/delta, 결과 renderer, SessionView |
| beforeText / beforeCorrection / afterText | 초기 기록 원문 보존, 단일 제안, confirm 저장, 상세·보고서·PDF·검색 payload |
| suggestions / reviews | 기존 12개 코드와 저장 테이블, 사용자 수락/거부, 과거 BEFORE/AFTER 행의 읽기 구분 |
| status / phase / revision | entity·transaction·controller·client·React route/state·동시 요청·cancel·open 목록 |
| currentProposal / confirmedResult | 미확정 복원, 설명 후 동일 ID 유지, 정정/실패 시 승인 잠금, 승인 후 확정 조회 |
| pending current-thought purpose | `check_current_thought`가 라이브 runtime에만 기록, help 교환만 있는 후속 TURN의 Agent가 Assessor routing에 사용, RESTORE에서는 전체 시간순 대화로 복구 |
| requestId / attemptNo / deadline | AiJobs 입력 선저장·재전달·만료·resync·최종 저장·취소·timeout과 reverse proxy |

NEW도 논리 job과 같은 생성 실패/retry 규칙을 적용한다. RESTORE에서 현존 실행을 덮어쓰지 않는다. snapshot은 Spring의 현재 revision과 실제 처리 중 사용자 메시지/논리job 식별을 포함한다. messageNumber는 전체 이력의 안정적인 번호이며 재개마다 마지막 몇 문장 기준으로 다시 붙이지 않는다. 과거 동일 timestamp는 저장 순서를 유지한다.

idempotency 중복 검사는 권한 확인 뒤 stale revision 검사보다 먼저 한다. 같은 키·다른 본문은 충돌이다. 동일 답변 문자열이라도 다른 논리 턴이면 새 발화다. 취소·approve에도 실제 재전달 정책을 적용한다. 일회성 retry 성공 후 같은 request 재전달이 새 generation을 시작하지 않게 한다.

PROPOSAL_REVIEW의 순수 설명/단순 동의는 기존 제안을 유지할 수 있다. latest session revision과 proposal.basedOnRevision은 같을 필요가 없다. confirm은 현재 If-Match·활성 proposalId·실행 중 job 부재를 확인한다. stale 화면·늦은 응답이 현재 상태를 덮어쓰지 않도록 React API 처리에도 revision을 적용한다. 사용자에게 내부 revision·job 용어를 노출할 필요는 없다.

이미 받은 값의 문법·타입과 실제 원문 인용의 존재를 한 경계에서 확인한다. 일반 질문의 적절성을 regex/route로 판정하는 층은 삭제한다. Assessor 거절·무효 후보를 성공 제안으로 저장하지 않는다. 사용자는 질문/제안 자체가 생성되지 못한 경우와 자연스러운 중단 안내를 구분할 수 있어야 한다. UI retry는 기술 생성 실패에, 다음 답변 입력은 일반 대화에 연결한다.

FastAPI timeout≤Spring client≤proxy/browser wait를 실제 설정으로 맞추되 바깥 무한 대기로 해결하지 않는다. job 저장의 deadline과 운영 실제 대기 한도를 일치시킨다. 기존 동기 요청이 완료되기 전에 실패로 반환됐으면 저장된 job 상태를 조회할 수 있게 한다. polling은 필요할 때만 사용한다.

DB 기존 관리 방식과 데이터 보존을 따른다. 새 not-null 필드를 기존 행에 바로 강제하거나 구형 JSON을 새 형식으로 무조건 역직렬화하지 않는다. read mapper·기본값·resultFormatVersion으로 호환을 유지하며 과거 semantic engine을 실행하지 않는다. 새 결과의 AFTER를 사용자 확인 값으로 표시하되 의미가 다른 legacy 대안 문구는 새 변화로 소급 재분류하지 않는다.

기록 분석, 인증, 통계, 기록 정렬/페이지, RAG 검색 권한과 임베딩 재시도는 유지한다. 기존 임베딩의 context / context+thought 목적을 함부로 전체 대화 임베딩으로 바꾸지 않는다. 보고서의 사용자 승인된 왜곡/생각 변화와 과거 두 라벨 집합 차이는 같은 수치가 아니다.

현재 생각 확인 목적은 외부 route·DTO·snapshot·DB 상태가 아니라 runtime 내부 routing 메타데이터다. 이 메타데이터는 AFTER 성립 여부를 판정하지 않으며, 실제 USER 답변을 Assessor에게 넘길 시점만 알려 준다. 프로세스 복원 뒤에는 Agent가 저장된 전체 ASSISTANT/USER 이력을 역추적한다. 확인 질문 뒤에 도움·설명·예시 교환만 있으면 결속을 회복하고, 안전·명시적 제어·주제 포기/전환·일반 질문·proposal 전이가 있으면 회복하지 않는다.

제품 prompt는 Agent, Assessor, 같은 Agent의 assessment-review 세 개이며 runtime `model-contracts.json`을 canonical source로 사용한다. provider-specific wrapper는 기계적 변환만 한다. 문서 JSON을 실제 provider 허용 schema라고 실행 없이 선언하지 않으며 실제 모델 수용은 별도 승인 뒤 canary에서 확인한다.
