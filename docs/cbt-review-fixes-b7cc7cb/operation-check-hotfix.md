# CBT 작업 종류와 기존 PostgreSQL CHECK 호환 수정

시작 기준 커밋: `9be16f6c535b3e1e6e6958d7f4ff274e5e72127d`.

기존 DB의 `ai_jobs_operation_check`가 새 enum 값 `CBT_COMMAND`를 거부하여 CBT 작업 이력 INSERT가 실패했다. 프런트 요청 형식이 아니라 Java enum과 기존 DB 제약조건 사이의 불일치다.

## 수정과 적용

`AiJobOperationConstraintInitializer`는 현재 프로젝트의 `spring.jpa.hibernate.ddl-auto=update`일 때 Hibernate 초기화 후, 애플리케이션 준비 완료 전에 실행된다. 기존 CHECK 식 전체를 보존하고 `OR operation = 'CBT_COMMAND'`만 추가한다. 데이터 행, 기존 QUESTION 작업, 다른 제약조건은 변경하지 않는다. 이미 해당 값이 포함된 CHECK와 해당 이름의 제약조건이 없는 DB는 변경하지 않는다. validate/none/create 설정에서는 실행하지 않는다.

기존 CHECK가 operation 한 열에 적용되는 로컬 제약인지 확인한다. 테이블의 실제 스키마를 조회하고 식별자를 인용한다. 동일 트랜잭션에서 잠금 후 재조회하고 제약을 교체하여 동시 시작과 중간 실패를 처리한다. 기존 validation/NO INHERIT 속성을 유지한다. 잠금 대기는 5초, SQL 실행은 30초로 제한하며 실패하면 롤백하고 시작을 실패시킨다. DB 계정에 해당 테이블의 ALTER 권한이 필요하다.

코드를 받은 뒤 백엔드를 재시작하면 현재 update 설정에서 적용된다. 이 수정 작업에서는 실제 DB 연결, 서버 시작, DDL 실행을 하지 않았다.

## 영향 요청

다음 다섯 경로는 모두 `InsightTransactions.job()`으로 CBT_COMMAND를 저장하므로 공통 제약 확장으로 함께 처리된다.

- POST /api/reflections/open: 첫 생성과 기존 세션의 요청 처리 이력
- POST /api/reflections/{id}/turn: 답변 처리
- POST /api/reflections/{id}/retry: 신형 및 구형 실패 입력의 새 시도
- POST /api/reflections/{id}/confirm: 결과 확정 이력
- POST /api/reflections/{id}/cancel: 종료 이력

중복 조회도 계속 CBT_COMMAND를 사용한다. 기존 구형 QUESTION 조회/호환 처리, STRUCTURE 분석과 EMBED 작업은 유지한다. 프런트와 AI 요청 계약은 변경하지 않는다.

## 검증 경계

`JAVA_HOME=C:\Program Files\Java\jdk-17`로 `gradlew.bat compileJava -x test --offline --no-daemon`을 실행하여 최종 코드 컴파일에 성공했다(BUILD SUCCESSFUL, 17초). `git diff --check`도 통과했다. 기존 지시에 따라 테스트는 `NOT_RUN_PENDING_GPT_FULL_BRANCH_REVIEW`이며, 실제 PostgreSQL에서 이전 CHECK/갱신 후 CHECK/새 DB/동시 시작/권한 및 잠금 실패와 다섯 요청 실행은 후속 검증 대상이다. 컴파일 성공을 DB 실행 성공으로 간주하지 않는다.

제약 교체의 잠금 및 validation 동작 참고: [PostgreSQL ALTER TABLE](https://www.postgresql.org/docs/17/sql-altertable.html), [pg_constraint](https://www.postgresql.org/docs/17/catalog-pg-constraint.html).
