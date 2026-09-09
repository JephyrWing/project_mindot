# Mindot CBT 전체 스택 개편 · 구현과 원격 push 전용 지시

이 문서 전문과 함께 받은 `Mindot-CBT-Full-Stack-Redesign-Package-20260909.zip`만으로 새 Codex 대화에서 시작한다. 이전 지시를 받았거나 적용했다고 전제하지 않는다.

**지금 할 일은 합의한 재설계의 구현·정적 확인·commit·원격 fix/CBTAI push·전체 리뷰 자료 제출이다. 사용자가 “push 뒤 ChatGPT가 브랜치 전체와 다른 기능 영향을 리뷰한 후 테스트”를 직접 지정했다. 오프라인 테스트·canary·유료 평가는 이번 단계에서 실행하지 않는다.**

## 1. 권한과 시작점

- 저장소: `JephyrWing/project_mindot`. 작업·push 브랜치: `fix/CBTAI`.
- 작성 때 확인한 원격 HEAD: `92618c194e8a70a13a8913425b5d396aea359e10`. 시작 시 최신 원격과 로컬 변경을 확인하고 현재 작업을 보존한다. 이 SHA로 강제 reset하지 않는다.
- 과거 AI 기준 `e10642279ad0c5fd927f12a188a2e766cc754064` 이후 기록 목록·정렬·일부 화면의 7개 커밋이 추가됐고 AI 경로는 그 비교에서 바뀌지 않았다. 이전 ZIP을 저장소 전체 위에 덮어씌우지 않는다.
- 이번 구조 개편에 한해 `mindot_ai`, `mindot_back`(Spring), `mindot_front`(React), 연관 DTO·저장·조회·보고서·검색·설정·테스트 수정이 승인돼 있다. 사용자와 팀원이 공유한 범위다. 주변 스택을 미승인 외부 과제로 남기지 말고 필요한 연결을 구현한다.
- 일반 commit과 원격 `fix/CBTAI` push도 승인돼 있다. develop/main merge, force push, 배포, DB 초기화/기존 데이터 삭제, 무관한 기능 재작성, 타인에게 메시지 전송은 포함하지 않는다.
- 사용자는 DB migration을 사용하지 않는다. Flyway/Liquibase·일괄 이관 스크립트를 추가하지 않는다. 실제 컬럼 변경은 기존 프로젝트 DB 관리 방식에 맞추고, 기존 `question_answers`는 읽어서 시간순 문맥을 구성한다. 과거 행 전체를 새 형식으로 덮어쓰지 않는다.

원격에 새 커밋이 있으면 정상 fetch/merge 또는 별도 작업 트리로 기존 변경을 보존해 통합한다. 새 변경이 있었다는 이유만으로 승인된 작업을 멈추지 않는다. 충돌은 양쪽 의도를 읽고 해결한다. 기존 worktree의 무관한 수정은 자신의 변경으로 커밋하지 않는다.

## 2. 읽기 순서와 우선순위

ZIP을 펼친 루트를 기준으로 다음을 읽는다. 현재 사용자 결정 → 이 작업 지시 → 아래 현재 계약 → 실제 코드 흐름 → 역사 자료 순서다. 평가 입력이 제품 기능을 확장하는 근거가 되지 않는다.

1. `docs/cbt-redesign/Mindot-CBT-Dialogue-Logic-Redesign.md`
2. `docs/cbt-redesign/Mindot-CBT-Redesign-Contracts.md`
3. `docs/cbt-redesign/model-contracts.json`과 `prompts/`의 5개 완성 원문, `implementation-notes.md`
4. `docs/cbt-redesign/Mindot-CBT-Full-Branch-Review.md`
5. `docs/cbt-evaluation/execution-after-review.md`, `offline-scenarios.md`, `canary-plan.json`, `known-revision-manifest.json`
6. 현재 저장소의 app/facade, 등록 route, 실제 호출 서비스와 model/provider, Spring controller/service/transaction/client/repository/entity, React router/API/component, 보고서와 RAG 소비자.

작업 시작 전 최신 iteration log도 읽는다. Drive ID `1SEXzKZPNTJzuzjrcjp8hiyBnMHU4oetu`, 링크 `https://drive.google.com/file/d/1SEXzKZPNTJzuzjrcjp8hiyBnMHU4oetu/view`. 접근할 수 없으면 함께 제공한 `reference/cbt-quality-iteration-log.md` snapshot을 쓰고 시각·hash·접근 한계를 기록한다. 로그의 최신 결정과 관련 실패를 읽으며 과거 지시를 현재 명세보다 우선시키지 않는다. 로그와 canonical 채점 기준의 외부 갱신은 ChatGPT가 담당한다.

과거 기준은 `reference/source-baseline.json`, `reference/Mindot-Project-Flow-Reference-20260909.md`에 있다. 과거 Blueprint/Schema는 의도 참고 자료이며 옛 API를 새 기능 요구로 되살리지 않는다. legacy-known, frozen-Q10, sealed 파일은 이후 평가/역사 보존용이다. 구현 프롬프트에 gold, caseId별 정답, 과거 실패 응답, 평가 문구를 주입하지 않는다. **봉인 key·평문은 열지 않는다.**

## 3. 구현할 제품 로직

최우선은 지금 대화에 맞는 CBT 질문 하나와 자연스러운 진행이다. 질문을 이해하지 못하면 설명·가상 예시로 돕는다. 실질 답변과 도움 요청이 함께 오면 둘 다 반영한다. 이미 답한 것·더 없음·모르겠음·생략·반복 항의를 구별하고 같은 내용을 다시 요구하지 않는다. 원문과 부분 정정을 시간순으로 읽는다. 모든 영역을 채우거나 고정 질문 순서를 강제하지 않는다.

실제 LangGraph Agent와 메모리, Writer/Assessor 도구, 같은 Agent의 최종 후보 검토를 유지한다. 일반 Agent 도구는 `write_turn(mode,goal)`, `assess_completion({})`, `respond_control({})`, `respond_safety(action,reason)` 네 개다. 자세한 필드는 model-contracts.json에 있다. 일반 턴마다 coverage/review/GOAL/atom/정정 연산/요청 출처 장부를 출력시키지 않는다. 문자열 분류기나 추가 추출·검증 LLM으로 같은 의미 엔진을 우회 재도입하지 않는다.

BEFORE는 처음 자동적 생각이다. AFTER는 **사용자가 그 초기 판단의 잘못을 알아차리고 수정한 생각**을 AI가 실제 대화에서 정리한 문장이다. 단순 현재 생각·추가 설명·맞장구·모름·기록 오타를 변화로 처리하지 않는다. 정해진 고백·AFTER 직접 입력·생각 쌍 사전 승인은 요구하지 않는다. 실제 단정을 불확실성으로 수정한 변화도 그 뜻대로 정리할 수 있다. 생각이 그대로면 유용한 질문 또는 기존 중단 안내로 진행하며 잘못을 인정할 때까지 반복하지 않는다.

실제 변화가 드러나고 당장의 도움/안전 요청을 처리했다면 Assessor가 AFTER와 기존 정의에 맞는 인지왜곡 후보를 함께 작성한다. 같은 Agent가 뜻 확대·사실 발명·정정 무시·BEFORE 혼동·근거를 검토한다. 두 생각의 유형을 각각 분류해 removed/persisted/new 차집합을 계산하지 않는다. 최종 인용의 원문 존재/USER 화자 검사는 한 경계에서 한다. AFTER 자체는 축자 인용과 같을 필요가 없다.

사용자에게 BEFORE·AFTER·제안과 이유를 한 번에 보여 주고 최종 확인 때 해당 AFTER와 수락/거부한 유형을 함께 저장한다. 전부 거부도 허용한다. 제안 뒤 단순 동의·설명 요청에는 동일 제안을 유지하며 재생성하지 않는다. 실질 뜻 정정이면 함께 갱신한다. “생각이 바뀐 게 아니다”라는 정정이면 철회하고 일반 대화로 돌아간다. 자연어 동의를 DB 승인으로 대체하지 않는다.

Assessor 전용 보충 질문·FACT_BOUNDARY_REQUIRED·gap·1회 예산·보충 대기·복원 장치를 삭제한다. 필요한 확인은 일반 CBT 질문이다. 명확한 현재 위험의 기본 대응은 보존하되 일반 질문에 과도한 안전 장부를 요구하지 않는다. 기술 실패를 사용자 위험이나 왜곡 없음으로 바꾸지 않는다.

## 4. 세 앱을 연결해 완성한다

Spring DB가 영구 정본이다. 처음/재개에 전체 원문·미승인 제안·현재 상태를 AI에 복원하고, 진행 중에는 새 답변만 보낸다. Agent는 자기 실제 질문과 돌아온 답을 누적한다. 메모리 유실은 모델 호출 전 RESYNC_REQUIRED → Spring DB full RESTORE → 같은 요청 한 번 재전달로 처리한다. 마지막 답변을 두 번 append하지 않는다. 단순 RESTORE와 저장된 제안 표시에는 모델 호출이 없다.

공개 API는 계약 문서의 open/turn/retry/confirm/cancel과 SessionView로 일치시킨다. turn의 새 사용자 본문은 `{"answer":"…"}`다. request key와 revision은 서버/헤더의 기계적 연결이며 모델에게 쓰게 하지 않는다. 사용자 답변 선저장과 결과 저장은 각각 짧은 트랜잭션으로 분리하고 AI 대기 중 DB 트랜잭션을 유지하지 않는다. 기존 AiJobs로 재시도·만료·중복·늦은 결과를 처리하며 새 큐/메모리 DB를 만들지 않는다.

React의 “나중에 이어하기”는 OPEN 유지, “성찰 완전히 중단/종료”는 기존 확인창과 cancel API로 CANCELLED, 제안 승인만 COMPLETED다. 별도 무변화 종료 상태·API를 만들지 않는다. 중단 문장만으로 모델이 영구 취소하지 않는다. 취소 뒤 결과를 저장하지 않고 문답은 보존한다.

기록 목록·상세·이어하기 화면·주간 보고서·PDF·RAG·임베딩 재시도까지 같은 confirmedResult 의미를 사용하도록 연결한다. 승인 전/취소 결과를 변화 사례로 집계하지 않는다. 전후 확신도는 **같은 초기 자동적 생각**에 대한 전후 수치다. 구형 결과의 의미와 데이터를 보존한다. 새 API/필드가 바뀐 모든 소비자를 추적하고, 기존 인증·기록 분석·정렬·페이지 기능의 후속 이상도 함께 살펴본다.

## 5. 프롬프트·용량·정적 확인

제공한 prompt 5개는 전체 교체 원문이다. 구 prompt에 덧붙이거나 실패 케이스 문구를 외우게 하지 않는다. schema/실제 코드와 충돌을 찾으면 기계적 연결을 수정하고 의미 변경이 필요하면 구체 내용을 보고한다. 완료를 가장해 새로운 의미 지시를 임의 누적하지 않는다.

제품 모델은 `gpt-4o-mini`. 시작 출력 cap은 Agent8192, Writer650, Assessor1800, 최종 검토1200, Writer 순수 형식 복구650이다. 일반2회, 완료3회, 제한 형식 복구 포함 요청당 최대3회, Moderation별도1회, SDK/framework 자동retry0이다. 필수 검토를 예약하지 못하면 Assessor부터 호출하지 않는다. 추가 Agent 재진입·후보 재작성·보충 Writer를 숨기지 않는다.

입력 **추정48,000토큰·UTF-8직렬화196,608바이트**는 지속 기본값이다. 과거12,000/96KiB로 복귀하지 않는다. system·대화·도구/응답 schema 포함 실제 요청을 측정하고 중복을 줄인다. 입력cap/출력cap/실제usage/누적예산을 구분한다. 이 단계에서는 실제 API 호출 없이 직렬화 측정과 설정 정합만 확인한다.

구현에 필요한 diff 검사, 문법/JSON/schema 검토, 모델·서버·DB를 실행하지 않는 타입·컴파일 확인은 수행하고 정확한 명령을 기록한다. 테스트 소스·fake fixture·runner 작성도 한다. **pytest/JUnit/vitest/통합/E2E/오프라인모델실행/canary/유료평가는 실행하지 않는다.** build/import에 test·서버·외부호출 hook이 붙어 있으면 실행하지 않고 정적 확인 방법을 쓴다. 정적 확인 결과를 테스트 통과 수로 보고하지 않는다.

## 6. push와 제출

1. 수정한 producer뿐 아니라 미수정 consumer를 읽고 전체 영향 지도와 구현 보고서를 작성한다. 작성한 테스트도 소스로 검토하되 실행은 보류한다.
2. 비밀값·운영 데이터·캐시를 제외하고 현재 필요한 자사 소스와 명세·테스트를 commit한다. 기존 파일을 임의 정리하거나 무관한 local 변경을 포함하지 않는다.
3. push 대상 workflow trigger를 확인한다. 작성 때 확인한 deploy.yml은 main push 대상이었으며 fix/CBTAI push에는 해당하지 않았다. 현재 workflow를 다시 확인하고 승인 규칙을 우회하지 않는다.
4. 원격 `fix/CBTAI`에 일반 push하고 remote SHA가 최종 로컬 SHA와 같은지 확인한다. 실패했으면 원인을 보존하고 파괴적 push로 해결하지 않는다. 사용자에게 이미 승인된 같은 push의 승인을 다시 묻지 않는다.
5. 최종 커밋의 세 앱 전체 자사 소스·설정·의존성·테스트·runner·제품 prompt와 manifest를 ZIP으로 만든다. 전체 tracked 목록, diff, 실제 변경 지도, 실행 명령/정적 결과, 미실행 상태, 원격 SHA를 함께 제공한다.
6. 가능한 경우 Drive `project_mindot/CBT AI 테스트 결과 모음/raw files`(부모ID `1PNOnM5Ykd9F_GAGP7yjT_FkFMrMA8vUR`) 아래 이번 구현 버전 폴더에 리뷰용 ZIP을 업로드하고 실제 ID/링크/bytes/hash를 확인한다. 연결이 없으면 로컬 ZIP과 정확한 경로를 완성해 제출하고 업로드 미완료를 명시한다. 연결 접근 한계 때문에 코드 작업을 미리 중단하지 않는다.

최종 상태: **READY_FOR_GPT_FULL_BRANCH_REVIEW**. 실제 push 실패라면 **PUSH_BLOCKED**와 로컬 SHA/완성 ZIP을 보고하며 성공으로 쓰지 않는다. 모든 테스트 상태는 **NOT_RUN_PENDING_GPT_FULL_BRANCH_REVIEW**다. 공식 평가 점수나 XLSX를 만들지 않는다.

최종 답변에 핵심 변경과 후속 기능 영향, remote branch/정확한 commit SHA, 소스 ZIP·보고서 링크, 정적 확인과 미실행 테스트를 적고 여기서 끝낸다. **다음 행동은 ChatGPT가 원격 브랜치 전체를 읽는 것이다. Codex 자체 리뷰 후 테스트로 자동 진입하지 않는다.**
