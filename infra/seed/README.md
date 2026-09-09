# 주간 리포트 시험용 더미데이터

`weekly-report-dummy.sql`은 로그인 가능한 전용 사용자와 주간 리포트의 원천 데이터를 생성합니다. `reports` 테이블의 집계 결과를 직접 넣지 않으므로 실제 주간 리포트 생성 로직을 끝까지 시험할 수 있습니다.

## 포함 데이터

- 리포트 대상: 실행일 기준 가장 최근에 끝난 월요일~일요일
- 선택 주 감정 기록 12건, 최근 8주 전체 감정 기록 22건
- 완료·사용자 확정 CBT 성찰 3건
- 인지왜곡 변화: 제거, 유지, 신규 사례
- 반복 감정 패턴: `LONG_TERM`, `SUSTAINED`, `REPEATED`, `RECENT`
- 긍정 감정 상황, 요일, 시간대 분포

## 실행

1. PostgreSQL을 실행하고 백엔드를 한 번 시작합니다. Hibernate가 테이블을 만들고 인지왜곡 기준 데이터 12종을 저장하기 위해 필요합니다.
2. 프로젝트 루트의 PowerShell에서 아래 명령을 실행합니다.

```powershell
Get-Content -Raw -Encoding UTF8 infra/seed/weekly-report-dummy.sql |
  docker compose -f infra/docker-compose.local.yml exec -T mindot-db `
    psql -U postgres -d mindot_db
```

3. 다음 계정으로 로그인합니다.

```text
이메일: weekly-report-test@mindot.local
비밀번호: Password1!
```

4. 주간 리포트 화면에서 이전 주로 이동해 리포트를 생성합니다. 정확한 대상 주는 SQL 실행 마지막 결과의 `report_week_start`와 `report_week_end`에 표시됩니다.

스크립트를 다시 실행하면 `weekly-report-v1` 시드가 만든 행과 전용 계정의 해당 기간 주간 리포트만 교체합니다. 다른 사용자의 데이터는 변경하지 않습니다.
