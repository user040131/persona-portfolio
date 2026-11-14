# quant-engine — Python 퀀트 엔진

설계: [docs/02 §4](../docs/02_시스템아키텍처설계서.md), [docs/03 §1·§3·§4.1](../docs/03_도메인_API_DB설계서.md)

시장 데이터 적재·갱신(배치)과 모든 계산(지표·점수·배분·평가·알림 조건·백테스트)을 맡는다.
사용자·설문·화면 문구는 모른다. Spring 서비스만 `X-Service-Key`를 붙여 부른다.

| 항목 | 버전 |
|---|---|
| Python | 3.12 |
| FastAPI / SQLAlchemy | 0.16x / 2.x |
| pandas | **2.x** (강의 코드 기준. 3.x는 문자열 dtype·복사 동작이 달라 쓰지 않는다) |
| DB | MySQL 8.4, `market` 스키마, `quant_user` 계정 (app 스키마는 권한 없음) |

## 구조

```
app/
├─ main.py                 # FastAPI 앱, 요청 로그(X-Request-Id), /livez
├─ config.py               # 연결·인증키·계산 파라미터(01 §5 소유=P)
├─ errors.py               # 공통 오류 형식 {"error": {code, message, detail}}
├─ security.py             # X-Service-Key 검사
├─ api/internal/v1/        # 내부 API: I1 health, I2 meta/data-status, I3 meta/sectors,
│                          #           I4 stocks, I5 stocks/{code}, I15 admin/reload
├─ data/market_data.py     # 시장 데이터 메모리 캐시 + snapshot(t) (미래 정보 차단)
├─ domain/indicators.py    # 지표 (PER·PBR·PSR·PCR·ROE·ROA·모멘텀·변동성·RSI·부채비율·배당수익률·시총)
├─ domain/screening.py     # 상장폐지 위험(D1~D5), 공통 필터
├─ api/deps.py             # get_db, require_data(데이터 없으면 503 DATA_NOT_LOADED)
├─ db/models.py            # market 스키마 15개 테이블
├─ data/status.py          # 적재 상태·데이터 버전
├─ domain/                 # 순수 계산 (DB·HTTP import 금지)
└─ etl/                    # 배치 (다음 단계: load_base)
config/sector_groups.json  # 업종 묶음
```

## 실행

가상환경 (처음 한 번):
```bash
py -3.12 -m venv .venv
```
```bash
.venv/Scripts/python.exe -m pip install -r requirements-dev.txt
```

테스트 (실제 MySQL 테스트는 Docker가 있을 때만 돈다):
```bash
.venv/Scripts/python.exe -m pytest -q
```

로컬 실행 (docker compose의 MySQL, 호스트 포트 3307):
```bash
.venv/Scripts/python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

컨테이너로 실행 (저장소 루트에서):
```bash
docker compose up -d --build quant-engine
```

확인:
```bash
curl -H "X-Service-Key: dev-only-change-me" http://127.0.0.1:8000/internal/v1/health
```

## 데이터 적재 (load_base)

제공 파일을 `data/raw/`에 표준 이름으로 모은 뒤 `market` 스키마에 넣는다. 처음 한 번만 원본 폴더를 알려 주면 된다:
```bash
.venv/Scripts/python.exe -m app.etl.load_base --source-dir "../기반 문서"
```

그 뒤로는(또는 컨테이너 안에서):
```bash
docker compose exec quant-engine python -m app.etl.load_base
```

- 장기 가격 파일에 KRX 영업일을 끝에서부터 붙이고, 샘플 가격과 겹치는 144만 칸을 대조해 하나라도 다르면 중단한다.
- 한 트랜잭션으로 시장 테이블을 비우고 다시 넣는다(백테스트 결과·캐시는 유지). 성공하면 `etl_log` id가 새 `data_version`이 된다.
- 로컬 기준 약 3분 (가격 292만 행 쓰기가 대부분).
- 엔진은 기동할 때 시장 데이터를 메모리에 올린다. 처음엔 약 35초, 그 뒤로는 `data/cache/market_v{버전}.pkl`로 1초 안쪽. 적재를 다시 했으면 엔진을 재시작하거나 `POST /internal/v1/admin/reload`를 부른다.
- 알려진 점: 원본의 KOSPI 열이 2026-06-03, 2026-07-17 이틀 비어 있다. `exchange_calendars`는 두 날을 영업일로 보지만 실제로는 휴장일(지방선거일·제헌절)로 보이며, 종목 가격은 전날 값이 그대로 들어 있다. 수익률 0인 날로 처리된다.

- API 문서: http://127.0.0.1:8000/internal/docs
- 제공 파일은 `data/raw/`에 둔다(git·이미지 제외, compose가 볼륨으로 붙임).
