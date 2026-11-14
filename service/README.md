# service — Spring Boot (모듈러 모놀리스)

설계: [docs/02 §3](../docs/02_시스템아키텍처설계서.md), [docs/03 §4.2·§6·§7](../docs/03_도메인_API_DB설계서.md)

| 항목 | 버전 |
|---|---|
| Java | 21 (Gradle 툴체인이 자동으로 찾거나 내려받음) |
| Spring Boot | 4.1.2 |
| Spring Modulith | 2.1.2 |
| Gradle | 9.8.2 (wrapper 포함) |

## 모듈

| Gradle 모듈 | 패키지 | 의존 가능 |
|---|---|---|
| common | `kr.team2.portfolio.common` | (모두가 씀) |
| quant-client | `…quantclient` | common |
| auth | `…auth` | common |
| profile | `…profile` | common, quantclient::api |
| portfolio | `…portfolio` | common, quantclient::api, profile::api |
| recommendation | `…recommendation` | common, quantclient::api, profile::api, portfolio::api |
| alert | `…alert` | common, quantclient::api, profile::api, portfolio::api, recommendation::api |
| backtest | `…backtest` | common, quantclient::api, profile::api |
| web | `…web` | 모든 모듈의 api |
| app | `kr.team2.portfolio`, `…app` | 전부 (조립·보안·Flyway) |

- 각 모듈은 `api`(다른 모듈 공개)와 `internal`(모듈 전용) 패키지로 나눈다.
- 규칙 위반은 `ModularityTests`가 잡아 빌드를 실패시킨다.
- 모듈 구성도: 빌드 후 `app/build/spring-modulith-docs/components.puml`

## 실행

MySQL 띄우기 (저장소 루트에서, Docker Desktop 필요). 호스트 포트는 **3307**이다(PC에 설치된 MySQL의 3306과 겹치지 않게):
```bash
docker compose up -d mysql
```

빌드·테스트:
```bash
./gradlew build
```

서버 실행 (http://localhost:8080). 먼저 저장소 루트에서 `docker compose up -d mysql quant-engine`으로 MySQL과 엔진을 띄우고(엔진 데이터 적재는 [quant-engine/README.md](../quant-engine/README.md)):
```bash
./gradlew :app:bootRun --args=--spring.profiles.active=local
```

| 프로필 | 엔진 | 쓸 때 |
|---|---|---|
| `local` | 실제 엔진 `http://127.0.0.1:8000` | 평소 개발 |
| `stub` | 대역 데이터 `quant-client/src/main/resources/quant-stub/stocks.json` (808종목, 2026-08-19) | 엔진 없이 화면만 만질 때 |
| (없음) | `QUANT_BASE_URL`, `QUANT_SERVICE_KEY` 환경변수 | 컨테이너 배포 |

- 실제 엔진과의 계약 확인: `QUANT_LIVE_URL=http://127.0.0.1:8000 ./gradlew :quant-client:test`

- MySQL 통합 테스트(`MySqlIntegrationTest`를 상속한 테스트)는 Testcontainers로 MySQL 8.4를 띄운다. Docker가 없으면 자동으로 건너뛴다.
- 포트는 환경변수 `PORT`로 바꿀 수 있다 (기본 8080).

## 로컬 테스트 계정

로컬 MySQL(docker compose)에 만들어 둔 개발용 계정. 실제 서비스 계정이 아니다.

| 아이디 | 비밀번호 |
|---|---|
| demouser | demo-pass-2026 |
| demobuyer | demo-pass-2026 |

`/signup`에서 새로 만들어도 된다. 아이디는 대소문자를 구분하지 않는다(소문자로 저장).
demouser는 골든 프로필(위험중립형, 3,000만 원, 삼성전자 20주)로 온보딩을 마친 상태다.
추천 1번(버전 4개: 기본 → 태경비케이 빼기 → 안전자산 60% → 자동으로 되돌리기)이 있고, 아직 매수 완료는 하지 않았다.
매수 완료를 누르면 첫 추천 금액이 비워져 이후 추천은 추가 투자 금액을 넣어야 한다.

demobuyer는 같은 골든 프로필(A5만 '15개 안팎')로 온보딩 → 추천 2번 매수 완료까지 한 상태다.
포트폴리오 화면에서 삼성전자를 30주·평균가 230,000원으로 고친 뒤, 종목 쏠림(R1) 알림의 제안을 "이대로 했어요"로 반영해
지금은 열린 알림이 없다 (삼성전자 8주).

## 화면

| 경로 | 내용 |
|---|---|
| `/profile` | 투자 성향, 보유 종목, **추천 받기** |
| `/portfolio` | 내 포트폴리오 (매수 완료 후): 원그래프(종목 조각을 누르면 상세), 목표 대비 차이, 업종 비중과 한도, 직접 고치기(수량·평균가·종목 추가/삭제·채권·현금, 저장 전에 그래프가 바로 바뀜), 추가 투자 |
| `/backtests`, `/backtests/{jobId}` | 백테스트 실행·목록, 결과(실험 A~D 탭, NAV·낙폭 그래프, 지표·연도별 표, 그해 담은 종목, CSV) |
| `/alerts/{id}` | 리밸런싱 제안: 사고팔 수량, 거래 전·후 비중, 이대로 했어요(반영)·알림만 닫기 |
| `/recommendations/{id}` | 추천 결과: 원그래프(바깥 주식·채권·현금 / 안쪽 종목), 근거 문장, 비중 방식 탭, 종목 빼기, 안전자산 비중, 왜 없나요, 기존 보유와 합친 목표 비중, 매수 완료 |

원그래프는 Chart.js 4.4.1을 jsDelivr CDN에서 읽는다. 인터넷이 없으면 그래프만 빠지고 나머지는 그대로 보인다.
