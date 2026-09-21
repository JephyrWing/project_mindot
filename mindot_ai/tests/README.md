# FastAPI 요구사항 테스트

[Notion 테스트 절차](https://app.notion.com/p/f119b85b36b383a4ae068183fe288159)의 `pytest + TestClient + mock` 방식으로 첨부 엑셀의 FastAPI 18개 요구사항을 검증한다. 테스트 파일은 엑셀에 적힌 `mindot_ai/tests/` 바로 아래 경로를 사용한다.

기존 `test_api_contract.py`, `test_records_api.py`의 검증 내용과 fixture를 유지하고 TC-ID별 클래스로 정리했다. OpenAPI 경로 확인, 오류 매핑, provider 장애 테스트를 보완했다. 나머지 시나리오는 엑셀에 지정된 파일에 작성했다.

사용자 정정에 따라 **도움됨 점수 3 이상 조건은 테스트하지 않는다.** 확정 AFTER/legacy 필드와 비공백 후보는 검증한다.

## 실행

프로젝트 가상환경과 애플리케이션 의존성이 준비된 상태에서 실행한다.

```powershell
cd D:\project_mindot\mindot_ai\tests
..\.venv\Scripts\python.exe -m pip install -r requirements-test.txt
..\.venv\Scripts\python.exe -m pytest -v
```

`pytest.ini`는 아래 8개 파일을 기본 실행 대상으로 지정한다. 기존 `cbt_test/` 코드는 변경하지 않았다. 실행 중인 서버, DB, 실제 API 키는 필요하지 않다.

HTML/JUnit 보고서 생성:

```powershell
..\.venv\Scripts\python.exe -m pytest -v --html=../artifacts/fastapi-tests/report.html --self-contained-html --junitxml=../artifacts/fastapi-tests/junit.xml
```

특정 파일만 실행:

```powershell
..\.venv\Scripts\python.exe -m pytest test_records_api.py -v
```

## 요구사항과 파일

| 파일 | 요구사항 ID | 내용 |
|---|---|---|
| test_api_contract.py | FA-FUNC-001, 002 | 기존 기본 응답·health·빈 입력·분석 실패 검사와 OpenAPI·추가 필드·409/502 보완 |
| test_records_api.py | FA-FUNC-004, 005 | 기존 정규화 검사와 timeout·예외·잘못된 출력 보완 |
| test_reflections_api.py | FA-FUNC-006~010 | NEW/RESTORE/TURN 요청·복원·revision·메시지 추가 |
| test_reflection_runtime.py | FA-FUNC-011, 012 | 재전송·충돌·명시적 retry·동시 요청·취소 후 commit 차단 |
| test_reflection_proposal.py | FA-FUNC-013, 014 | 잘못된 후보 거절·proposal 필드·설명·철회 |
| test_safety_preemption.py | FA-FUNC-015, 016 | 자살·타인 위해 징후 선행 중단·최소쌍·moderation 장애 |
| test_provider_contract.py | FA-FUNC-017 | 용량·호출 순서·횟수·malformed receipt |
| test_pattern_api.py | FA-FUNC-018, 019 | 요청 경계·확정 생각 후보·코드 및 문장 후처리 |

`test_api_contract.py`는 `Test_TC_FA_FUNC_001`, `Test_TC_FA_FUNC_002`로, `test_records_api.py`는 `Test_TC_FA_FUNC_004`, `Test_TC_FA_FUNC_005`로 묶었다. 클래스 설명에 엑셀의 원래 TC-ID를 적고 기존 테스트 함수 이름은 유지했다.

JUnit 결과에 `requirement_id`, `tc_id`, `layer` 속성을 기록한다. `conftest.py`는 클래스 이름에서 ID를 연결한다. 각 요구사항의 모든 케이스가 통과했을 때 해당 요구사항을 통과로 판단한다. 원본에 없는 FA-FUNC-003은 추가하지 않았다.

## 검증 범위

- CBT는 실제 API·상태 관리·LangGraph·Provider·Budget·파서를 실행하고 SDK 전송과 moderation만 대체한다. 감정 기록·패턴은 agent의 `ainvoke`를 대체하고 실제 정규화·후처리를 검증한다.
- FA-FUNC-005의 호출 횟수는 서비스의 agent 호출 경계에서 검증한다. SDK 내부 재시도나 실제 시간 경과는 측정하지 않는다. 유료 호출·LLM 의미 품질·전체 안전성·Spring 저장·프론트 흐름은 이 테스트 범위에 포함하지 않는다.
- 네트워크 HTTP 전송을 차단한다. Windows asyncio 내부 통신용 loopback socket은 허용한다. 새로운 환경에서는 온라인 준비 단계에서 `python -c "import tiktoken; tiktoken.get_encoding('o200k_base')"`를 한 번 실행해 tokenizer 캐시를 준비한다.
- FA-FUNC-015의 안전 중단 대상은 사용자가 명시한 자살 징후·타인 위해 징조다. 출혈 자체를 중단 대상으로 보는 테스트와 결함 판정은 제거했다.

실패가 있으면 pytest 종료 코드는 1이다. 실제 결과는 생성한 HTML/JUnit 보고서로 확인한다.
