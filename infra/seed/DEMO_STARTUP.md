# 로컬·배포 시작 시 시연 데이터 자동 적용

기존 `application.yml` 하나에서 `MINDOT_DEMO_SEED_ENABLED`를 읽으며 기본값은 `true`다.
별도 프로필 설정 파일 없이 로컬 JVM과 배포 컨테이너 모두 같은 방식으로 자동 적용한다.
새 이미지를 배포하면 Hibernate 스키마 초기화와 인지왜곡 기준 코드 초기화 뒤에
`DemoSeedInitializer`가 같은 백엔드 DataSource에 동봉된 seed를 적용한다.
`MINDOT_DEMO_SEED_ENABLED=false`이면 실행하지 않는다.

## 들어가는 데이터

- 기존 사용자 `users.id=1`, 시간대 `Asia/Seoul` 대상
- 2026-03-22~2026-09-17 감정 1,000개와 완료·확정 CBT 200개
- 감정 검색 벡터 1,000개, CBT context/thoughtAware 벡터 각 200개
- BEFORE/CONFIRMED 인지왜곡 유형 343개

원문·대화·점수는 합성 시연 자료다. 실제 OpenAI 임베딩은 이미 생성되어 있어
시작 시 외부 API 호출이나 추가 과금은 없다. 날짜는 패키지 기준일 2026-09-18로 고정된다.
서버 시작일에 맞춰 날짜를 이동하거나 데이터를 새로 생성하지 않는다.

## 재시작과 실패 처리

`ai_meta.demoSeed.namespace=mindot-demo-u1-v1`과 seedKey로 기존 행을 찾고,
원본 해시·실제 컬럼·벡터가 같은 행은 재사용한다. 재시작해도 중복 INSERT하지 않는다.
ID는 DB가 배정하고 감정→CBT→유형 관계를 연결한다. 시작 ID를 20으로 고정하거나
기존 시퀀스를 재설정하지 않는다. 실제 기록·다른 사용자·사용자 설정은 수정하지 않는다.

새 DB에 사용자 1이 없으면 데이터 적용을 보류하고 안내 로그와 함께 정상 시작한다.
팀원이 제품의 정상 회원가입 경로로 사용자 1을 만든 뒤 백엔드를 재시작하면 적용된다.
사용자·동의값은 seed가 생성하지 않는다.

시간대 불일치, schema/code 불일치, 같은 namespace의 다른 데이터,
수정된 seed 행 등의 충돌은 전체 트랜잭션을 롤백하고 애플리케이션 시작을 실패시킨다.
상세 원문·벡터·SQL 전체는 로그에 출력하지 않는다.
DB identity sequence는 롤백 시 번호에 빈 구간이 남을 수 있다.

SQL의 advisory lock과 테이블 잠금으로 동시 적용을 직렬화한다. 잠금 대기는 10초,
JDBC 실행 제한은 180초다. 충돌 시 내용을 임의로 덮어쓰거나 삭제하지 않는다.
기존 시연 기록을 수정·삭제하며 사용하려면 성공적으로 한 번 적용한 후 아래 설정으로
자동 검증/적용을 끌 수 있다. 자료를 새 버전으로 교체할 때는 namespace와 배포 계획을 검토한다.

```dotenv
# 배포 서버 infra/.env.prod (env_file로 backend에 전달됨)
MINDOT_DEMO_SEED_ENABLED=false
```

설정 변경 후 backend 컨테이너를 재생성한다. 로컬 JVM 실행에서도
`MINDOT_DEMO_SEED_ENABLED=true`로 명시적으로 켜거나
`--mindot.demo-seed.enabled=true` 애플리케이션 인자를 사용한다.

## 데이터 배포와 재현

배포에 필요한 파일은 `mindot_back/src/main/resources/db/demo/`에 Git 추적 대상으로 포함된다.
Docker 빌드가 이 리소스를 JAR 안에 넣으므로 서버에 ZIP을 복사하거나 `artifacts/`를 배포할 필요가 없다.
`artifacts/`는 로컬 원본·캐시·감사 출력 전용이며 `.gitignore`로 제외한다.
`.sql.gz`는 검증된 SQL을 압축한 파일이며, 실행 전 압축 해제한 bytes의 SHA256을 검사한다.

완성된 Embedded ZIP을 새 폴더에 풀고 저장소 루트에서 실행하면 동일 리소스를 재생성한다.
원본 v1.0 ZIP에는 실제 임베딩이 없으므로 입력으로 쓸 수 없다.

```text
python infra/seed/prepare_demo_seed.py --package-dir <Mindot-Demo-Seed-Embedded-v1.0-20260918 폴더>
```

`provenance.json`에 원본/번들 SQL 해시, 기준 커밋, 모델·차원·수량을 기록했다.
제품 스키마를 seed에 맞춰 변경하는 migration은 추가하지 않는다.

## 시연 리포트

seed는 완성 리포트를 삽입하지 않는다. 사용자 1로 정상 로그인·동의한 후 아래 API로 갱신한다.
GET만 하면 기존 캐시가 보일 수 있다. 기존 실제 기록이 있으면 합계는 증가한다.

```text
POST /api/reports/weekly?weekStart=2026-09-07
POST /api/reports/monthly?month=2026-08
POST /api/reports/monthly?month=2026-04
```
