# 03. 도메인 / API / DB 설계서

> 버전: 3.0 (2026-11-11) · 대상 독자: 백엔드 개발자 (Spring 팀, Python 팀)
> 관련: [01 요구사항](01_요구사항명세서.md) · [02 아키텍처](02_시스템아키텍처설계서.md) · [기능정의서](../기능정의서.md)
> 숫자 파라미터 이름은 01 §5와 같다. 코드의 `P.이름`은 그 값이다.

| 절 | 내용 | 담당 |
|---|---|---|
| §1 | 계산 규칙 (공식·의사코드) | **Python** |
| §2 | 공통 규약·오류 형식 | 공통 |
| §3 | 내부 API (`/internal/v1`) — **두 팀의 계약** | Python 구현, Spring 호출 |
| §4 | DB (MySQL `market`, `app`) | 각자 |
| §5 | 설정 파일 | 각자 |
| §6 | Spring 도메인 (프로필 계산, 추천 버전, 문구, 매수, 알림 상태, 백테스트 작업) | **Spring** |
| §7 | 공개 API (`/api`) + 화면 경로 | **Spring** |
| §8 | 테스트 케이스 (손계산 기대값) | 각자 |

---

# §1. 계산 규칙 — Python 퀀트 엔진

## 1.1 열거형 (`domain/enums.py`)

```python
class Grade(StrEnum):        STABLE="STABLE"; NEUTRAL="NEUTRAL"; AGGRESSIVE="AGGRESSIVE"
class Factor(StrEnum):       VALUE="VALUE"; QUALITY="QUALITY"; MOMENTUM="MOMENTUM"; STABILITY="STABILITY"; DIVIDEND="DIVIDEND"
FACTOR_ORDER = ["VALUE","QUALITY","MOMENTUM","STABILITY","DIVIDEND"]
class Method(StrEnum):       EQUAL="EQUAL"; INVERSE_VOL="INVERSE_VOL"; MIN_VARIANCE="MIN_VARIANCE"
class Asset(StrEnum):        BOND="BOND"; CASH="CASH"          # 주식은 종목코드 문자열

class Exclusion(StrEnum):    # "왜 없나요" 사유. 위에서부터 먼저 판정
    NO_PRICE; DELIST_RISK; NO_FINANCIAL; LOSS_MAKING; INSUFFICIENT_HISTORY; SMALL_CAP     # 공통
    HIGH_VOLATILITY; OVERHEATED; EXCLUDED_SECTOR                                           # 성향
    USER_REMOVED; PRICE_TOO_HIGH; SECTOR_LIMIT; RANK_OUT                                   # 선택

class DelistRule(StrEnum):   D1; D2; D3; D4; D5
class AlertType(StrEnum):    R1; R2; R3; R4; R5; R6; R7
class Warning(StrEnum):      COMBINED_LOSS_LIMIT; LOSS_LIMIT_EXCEEDED; MIN_VARIANCE_FALLBACK; N_REDUCED; NO_STOCK_ALLOCATION
```

> 한국어 라벨(등급명, 팩터명 등)은 Python에 두지 않는다. 화면 문구는 Spring 책임이다(§6.3).

## 1.2 입력 계약: `ProfileSettings`

Spring이 계산해(§6.1) 모든 계산 요청에 담아 보낸다. Python은 이 값만 쓰고 설문 답은 모른다.

```python
class ProfileSettings(BaseModel):
    grade: Grade
    loss_limit: float | None          # None = 제한 없음, 그 외 음수
    min_cash: float                   # 0~1
    stock_cap: float                  # 0~1
    n_stocks: int                     # 10 / 15 / 20
    factor_weights: dict[Factor, float]   # 합 1
    vol_exclude_top_pct: float        # 0 / 0.10 / 0.30
    exclude_overheat: bool
    stock_limit: float                # 주식 안 종목당 한도
    sector_limit: float               # 주식 안 업종당 한도
    default_method: Method
    excluded_sectors: list[str]
```

`settings_hash` = 키를 정렬한 JSON의 SHA-256 (과거 성과 카드 캐시 키, Python이 계산).

## 1.3 채권 지수 (`domain/bond.py`)

```python
def bond_returns(rate_pct: pd.Series, duration=P.bond_duration) -> pd.Series:
    y = rate_pct / 100                               # 영업일 index, 결측은 ffill
    return (y.shift(1) / 252 - duration * (y - y.shift(1))).fillna(0.0)

def bond_index(rate_pct): return 100 * (1 + bond_returns(rate_pct)).cumprod()
```
- 영업일 맞추기: `rate.reindex(trading_days).ffill()`. 첫 영업일(2010-01-04) 지수는 100이다.
- 확인값: 2026-08-19 기준 최근 7년 최대 하락은 약 -6.3%다(2022년).

## 1.4 시장 데이터 캐시 (`data/market_data.py`)

| 속성 | 형태 |
|---|---|
| `close`, `returns` | DataFrame index=날짜, columns=코드 |
| `kospi_ret`, `bond_ret`, `bond_idx` | Series |
| `financial` | DataFrame (code, fiscal_year, apply_date, 값들, source) |
| `stocks` | DataFrame index=code (name, sector, sector_group, is_managed) |
| `trading_days`, `as_of`, `data_version` | |

```python
def expected_fiscal_year(t):
    return t.year - 1 if (t.month, t.day) >= (P.fin_apply_month, P.fin_apply_day) else t.year - 2

def snapshot(self, t) -> Snapshot:
    fy = expected_fiscal_year(t)
    fin = self.financial[(self.financial.fiscal_year == fy) & (self.financial.apply_date <= t)].set_index("code")
    return Snapshot(t, self.close.loc[:t], self.returns.loc[:t], self.kospi_ret.loc[:t], self.bond_ret.loc[:t], fin, self.stocks)
```
> 미래 정보 차단은 `snapshot()` 한 곳에서 보장한다. 기대 회계연도 행이 없거나 값이 비면 "재무 없음"이다.

## 1.5 지표 (`domain/indicators.py`)

재무 금액은 **천원**, 주당값은 **원**이다.

| 열 | 공식 | NaN 조건 |
|---|---|---|
| `close` | `close_hist.iloc[-1]` | 종가 없음 |
| `shares_est` | `total_equity * 1000 / bps` | bps ≤ 0 |
| `mcap` | `close * shares_est` | |
| `per` / `pbr` / `psr` / `pcr` | `close / eps` / `bps` / `sps` / `ceps` | 분모 ≤ 0 |
| `roe` / `roa` | `net_income / total_equity` / `total_assets` | net_income ≤ 0, operating_income ≤ 0, 분모 ≤ 0 |
| `momentum_6m` | `close_hist.iloc[-1] / close_hist.iloc[-121] - 1` | 행 ≤ 120, 결측 |
| `vol_1y` | `returns_hist.iloc[-252:].std(ddof=1) * sqrt(252)` | 유효 개수 < 200 |
| `rsi_14` | `gain/(gain+loss)`, 최근 14일 변화 평균 (강의 `rs/(1+rs)`와 같음). 둘 다 0이면 0.5 | 창 안 결측 |
| `debt_ratio` | `total_liabilities / total_equity` | total_equity ≤ 0 |
| `div_yield` | `abs(dividends) * 1000 / mcap` (dividends 결측이면 0) | mcap NaN |
| `revenue_won` | `revenue * 1000` | |

**골든 값** (005930, 2026-08-19): close 247500, per 37.705667, pbr 3.867369, psr 5.002931, pcr 18.114616, roe 0.103609, roa 0.079738, momentum_6m 0.216216, vol_1y 0.731913, rsi_14 0.611429

- `vol_1y`가 0이면(1년 내내 종가가 같음 = 거래정지) 결측으로 본다 → 공통 필터 `INSUFFICIENT_HISTORY`. 0을 두면 변동성 역수 비중이 1/0이 된다 (2018·2022·2026년 4월 실제 사례, 2026-08-19 기준 000300).

## 1.6 거르기 (`domain/screening.py`)

### 공통 필터 — 순서대로, 처음 걸린 사유만 기록

| 순서 | 사유 | 조건 |
|---|---|---|
| 1 | `NO_PRICE` | close NaN |
| 2 | `DELIST_RISK` (+ 해당 규칙 목록) | D1 `mcap < 5e10` / D2 `revenue_won < 3e10` (금융 그룹 제외) / D3 `close < 1000` / D4 `total_equity < capital × 0.5` / D5 관리종목 (`use_managed=True`일 때만) |
| 3 | `NO_FINANCIAL` | 기대 회계연도 행 없음 또는 eps·bps·sps·ceps·net_income·operating_income·total_equity·total_assets 중 결측 |
| 4 | `LOSS_MAKING` | per·pbr·psr·pcr·roe·roa 중 NaN |
| 5 | `INSUFFICIENT_HISTORY` | momentum_6m·vol_1y·rsi_14·debt_ratio·div_yield 중 NaN |
| 6 | `SMALL_CAP` | 남은 종목 안에서 `mcap.rank(method="first") / n ≤ 0.10` |

- 백테스트는 `use_managed=False`로 돌린다. 관리종목 목록이 2026년 한 시점뿐이기 때문이다.
- `delist_rules(code, snap) -> list[DelistRule]`는 2단계만 따로 평가한다. 종목 목록 API와 R7에서 쓴다.

### 성향 필터 — 점수 계산 뒤

| 사유 | 조건 |
|---|---|
| `HIGH_VOLATILITY` | `vol_1y.rank(ascending=False, method="first") / n ≤ settings.vol_exclude_top_pct` (pct 0이면 건너뜀) |
| `OVERHEATED` | `settings.exclude_overheat and rsi_14 ≥ P.rsi_overheat` |
| `EXCLUDED_SECTOR` | `sector ∈ settings.excluded_sectors` |

## 1.7 업종 중립 점수 (`domain/scoring.py`)

`config/sector_groups.json`(§5.2)으로 업종을 그룹에 넣는다. 묶음에 없는 업종은 업종명이 곧 그룹이다.

```python
INDICATORS = {"VALUE": [("per",False),("pbr",False),("psr",False),("pcr",False)],
              "QUALITY": [("roe",True),("roa",True)], "MOMENTUM": [("momentum_6m",True)],
              "STABILITY": [("debt_ratio",False)], "DIVIDEND": [("div_yield",True)]}

def score(u, groups, weights, neutral=True, min_size=P.sector_min_size):
    g = groups.loc[u.index].copy()
    if neutral:
        g[g.map(g.value_counts()) < min_size] = "__ALL__"
    else:
        g[:] = "__ALL__"
    out = pd.DataFrame(index=u.index)
    for f, cols in INDICATORS.items():
        parts = []
        for col, hi in cols:
            s = pd.Series(index=u.index, dtype=float)
            for grp, idx in g.groupby(g).groups.items():
                pool = u.index if grp == "__ALL__" else idx           # __ALL__은 유니버스 전체와 비교
                rk = u.loc[pool, col].rank(ascending=not hi, method="average")
                s.loc[idx] = 1 - rk.loc[idx] / len(pool)
            out[f"s_{col}"] = s; parts.append(s)
        out[f"f_{f}"] = pd.concat(parts, axis=1).mean(axis=1)
    out["total"] = sum(weights[f] * out[f"f_{f}"] for f in FACTOR_ORDER)
    out["group"] = g
    out["group_n"] = [len(u) if x == "__ALL__" else int((g == x).sum()) for x in g]
    out["rank_in_group"] = out.groupby("group")["total"].rank(ascending=False, method="first").astype(int)
    return out.sort_values("total", ascending=False, kind="mergesort")   # 입력이 코드 오름차순 → 동점은 코드 순
```

## 1.8 포트폴리오 구성

### 1.8.1 종목 선택 (`domain/selection.py`)

```python
def select(ranked, close, sector, n, stock_budget, existing_values: dict[str, float], settings):
    n = min(n, int(stock_budget // P.min_amount_per_stock))            # 종목당 최소 50만 원
    if n == 0: return [], {}
    per = stock_budget / n
    pool = sum(existing_values.values()) + stock_budget
    sec_val = 기존 보유 업종별 평가금액 합
    picks, skipped = [], {}
    for c in ranked:
        if len(picks) == n: break
        if close[c] > per:                                       skipped[c] = "PRICE_TOO_HIGH"; continue
        if (sec_val[sector[c]] + per) / pool > settings.sector_limit: skipped[c] = "SECTOR_LIMIT"; continue
        picks.append(c); sec_val[sector[c]] += per
    return picks, skipped                       # 나머지 통과 종목은 RANK_OUT
```

### 1.8.2 주식 안 비중 (`domain/weighting.py`)

```python
def cap_weights(w, cap):                       # 넘치는 만큼 나머지에 비례 배분
    w = w.copy()
    for _ in range(len(w)):
        over = w > cap + 1e-12
        if not over.any(): break
        excess = (w[over] - cap).sum(); w[over] = cap
        free = ~over & (w < cap - 1e-12)
        w[free] += excess * w[free] / w[free].sum()
    return w

def weights_all(codes, vol, returns_hist, stock_limit):
    cap = max(stock_limit, 1 / len(codes))
    ew = cap_weights(pd.Series(1/len(codes), index=codes), cap)
    iv = cap_weights((1/vol[codes]) / (1/vol[codes]).sum(), cap)
    R = returns_hist[codes].iloc[-P.vol_lookback:].dropna(how="any")
    if len(R) < P.cov_min_obs: mv, fallback = iv, True
    else:
        cov = R.cov().values * 252
        res = minimize(lambda w: np.sqrt(w @ cov @ w), np.full(len(codes), 1/len(codes)), method="SLSQP",
                       bounds=[(0, cap)] * len(codes), constraints=({"type":"eq","fun":lambda w: w.sum()-1},),
                       options={"ftol":1e-12, "maxiter":1000})            # 강의 asset_allocation_26.py MVP
        x = np.clip(res.x, 0, None)
        mv, fallback = (pd.Series(x / x.sum(), index=codes), False) if res.success else (iv, True)
    return {"EQUAL": ew, "INVERSE_VOL": iv, "MIN_VARIANCE": mv}, fallback
```
비중은 소수 4자리로 반올림하고, 합이 1이 되도록 가장 큰 비중에서 오차를 보정한다.

### 1.8.3 주식·채권·현금 비율 (`domain/allocation.py`)

```python
def basket_returns(w, returns_hist, kospi_ret, start):
    R = returns_hist.loc[start:, w.index]; avail = R.notna()
    num = (R.fillna(0) * w).sum(axis=1); den = (avail * w).sum(axis=1)
    return (num / den).where(den > 0, kospi_ret.loc[start:])          # 상장 전 구간은 KOSPI로 대신

def mdd(r): v = (1 + r).cumprod(); return float((v / v.cummax() - 1).min())

def allocate(w, snap, settings) -> (s, b, binding, expected_mdd):
    t = snap.date
    start = max(t - pd.DateOffset(years=P.mdd_lookback_years), 첫 영업일)
    if (t - start).days < 365 * P.mdd_min_years: raise Conflict("INSUFFICIENT_HISTORY")
    rS = basket_returns(w, snap.returns_hist, snap.kospi_ret_hist, start); rB = snap.bond_ret_hist.loc[start:]
    c_min, cap = settings.min_cash, settings.stock_cap
    L = None if settings.loss_limit is None else settings.loss_limit * P.loss_limit_margin   # 여유 70% [8-5b]
    shock = P.bond_rate_shock * P.bond_duration                                             # 채권 금리 충격 손실 5.6%
    top = round(min(cap, 1 - c_min), 2)
    if L is None:
        s, b = top, round(1 - c_min - top, 2); return s, b, "CAP", mdd(s*rS + b*rB)
    for s in np.arange(top, -1e-9, -P.allocation_grid_step):
        for b in np.arange(round(1 - c_min - s, 2), -1e-9, -P.allocation_grid_step):
            m = mdd(s * rS + b * rB)
            if m >= L - 1e-12 and -b * shock >= L - 1e-12:
                return round(s,2), round(b,2), ("CAP" if abs(s - top) < 1e-9 else "LOSS_LIMIT"), m
    return 0.0, 0.0, "ALL_CASH", 0.0
```

- 현금 = 1 − s − b
- **반복 맞추기**: 1회차는 `stock_budget = 투자금 × stock_cap`으로 선택·비중을 정하고 `allocate`한다. 2회차부터는 `stock_budget = 투자금 × s`로 다시 고른다. 새 주식 몫으로 골라도 종목이 같으면 멈추고, 최대 5회까지 한다. 끝내 수렴하지 않으면 마지막 주식 몫으로 고른 종목을 쓰고 예상 하락폭만 다시 잰다.
- **담을 종목이 없으면 주식 0%** (주식 몫이 종목당 최소 50만 원도 안 될 때). 빈 바구니를 '위험 없는 주식'으로 보지 않도록 주식 상한을 0으로 두고 채권·현금만 다시 고른다. binding은 `LOSS_LIMIT`, 경고 `NO_STOCK_ALLOCATION`.
- **최대 하락폭은 시작값 1도 고점으로 본다** (첫날 하락도 하락이다). 구현 중 단위 테스트(T-ALC-01)로 잡은 오류.

### 1.8.4 주문·합산 (`domain/orders.py`)

```python
stock_amt = 투자금 * s; bond_amt = int(투자금 * b)
amount[c] = int(stock_amt * w[c]); shares[c] = amount[c] // close[c]; spend[c] = shares[c] * close[c]
cash_from_rounding = int(stock_amt) - sum(spend); cash = 투자금 - sum(spend) - bond_amt
bond_units_to_buy = bond_amt / bond_idx[as_of]

combined value = 기존 종목(수량×종가) + spend ; BOND = 기존 단위×지수 + bond_amt ; CASH = 기존 현금 + cash
combined.target_weights = combined value / total      # 매수 완료 시 Spring이 목표 비중으로 저장
combined.expected_mdd = 같은 기간·같은 방식으로 합산 비중 시뮬레이션
L is not None and combined.expected_mdd < L → 경고 COMBINED_LOSS_LIMIT
```

### 1.8.5 수정 반영 (`edits`)

| 수정 | 처리 |
|---|---|
| `removed_codes` | 해당 종목을 `USER_REMOVED`로 빼고 1.8.1부터 다시 |
| `safe_ratio` (0~1) | 1.8.3 대신 `s = 1 − safe_ratio`. 채권:현금은 자동 계산 결과의 비율을 유지하되 현금 ≥ min_cash. `mdd < L`이면 경고 `LOSS_LIMIT_EXCEEDED`, binding은 `USER` |
| `method` | `method_selected`만 바꿈 (세 방식은 항상 계산) |

## 1.9 근거 사실 (`domain/facts.py`) — 문구 아님

Python은 **코드와 숫자만** 만든다. 문장은 Spring이 §6.3 템플릿으로 만든다.

### 종목 사실 (`stocks[].facts`)

```json
{
  "selection":    {"top_factors": ["VALUE", "QUALITY"], "group": "운송장비·부품", "group_n": 41, "rank_in_group": 2},
  "level":        {"per": 4.12, "pbr": 0.71, "roe": 0.173},
  "profile_link": {"factor": "STABILITY", "pct_top": 0.08, "value": 0.35},
  "cautions":     [{"code": "HIGH_VOL", "vol_1y": 0.52}, {"code": "OVERHEAT", "rsi_14": 0.74}, {"code": "WEAK_FACTOR", "factor": "MOMENTUM"}],
  "role":         {"code": "DIVERSIFY", "sector": "운송장비·부품"}
}
```

| 사실 | 규칙 |
|---|---|
| `selection.top_factors` | 가중치 > 0인 팩터 중 팩터 점수 상위 2개 |
| `profile_link.factor` | 가중치가 가장 큰 팩터. `pct_top` = 1 − 그 팩터 대표 지표 점수(VALUE→per, QUALITY→roe, MOMENTUM→momentum_6m, STABILITY→debt_ratio, DIVIDEND→div_yield), `value` = 그 지표 값 |
| `cautions` | `HIGH_VOL`: vol_1y 상위 20% / `OVERHEAT`: rsi ≥ 0.70 (공격형만 남아 있음) / `WEAK_FACTOR`: 팩터 점수 < 0.3 |
| `role.code` | 첫 번째 맞는 것: `HELD`(보유 중) → `NO_HOLDINGS` → `DIVERSIFY`(보유 업종에 없음) → `OVERLAP` |

### 포트폴리오 사실 (`portfolio_facts`)

```json
{"stock_binding": "LOSS_LIMIT", "expected_mdd": -0.0987, "lookback_years": 7,
 "stock_cap": 0.55, "n_requested": 15, "n_final": 12, "n_reduced": true,
 "skipped": {"PRICE_TOO_HIGH": 1, "SECTOR_LIMIT": 2, "HIGH_VOLATILITY": 31},
 "cash_from_rounding": 83100, "method_selected": "INVERSE_VOL"}
```

### 탈락 사유 (`screening`) — "왜 없나요"용

가격이 있는 모든 종목에 대해:
```json
{"005930": {"stage": "PROFILE", "reason": "HIGH_VOLATILITY", "params": {"pct": 0.04}},
 "000270": {"stage": "PICKED", "reason": null, "params": {}},
 "011070": {"stage": "COMMON", "reason": "DELIST_RISK", "params": {"rules": ["D1", "D3"]}},
 "035420": {"stage": "SELECTION", "reason": "RANK_OUT", "params": {"group": "IT 서비스", "group_n": 18, "rank_in_group": 9}}}
```
`stage`는 `COMMON | PROFILE | SELECTION | PICKED`다. `params`는 §6.3 템플릿에 필요한 숫자다(PRICE_TOO_HIGH → close, SECTOR_LIMIT → limit, OVERHEATED → rsi_14).

## 1.10 평가와 알림 조건 (`domain/alerts.py`)

```python
value[c] = qty * close[c]; value["BOND"] = bond_units * bond_idx[t]; value["CASH"] = cash
total = Σ value; cur = value / total; w_in = 주식만의 비중(주식 합 기준)

R1: c, w_in[c] > settings.stock_limit                         → target=c,  detail {weight, limit}
R2: 업종 s, Σ w_in(s) > settings.sector_limit                  → target=s,  detail {weight, limit}
R3: 자산 a, |cur[a] − targets.get(a,0)| > P.rebalance_band     → target=a,  detail {current, target}
R4: cur[BOND]+cur[CASH] < targets[BOND]+targets[CASH] − band   → target="", detail {current, target}
R5: since = max(purchased_at, holdings_changed_at); since 이후 매일 (현재 수량 고정) 총가치 → dd = 현재/고점 − 1
    settings.loss_limit 있고 dd ≤ loss_limit × P.loss_alert_ratio → target="", detail {drawdown, limit}
R6: t ≥ 올해 4월 첫 영업일, purchased_at < 그 날짜, last_r6_year < 올해 → target="", detail {year}
R7: c, delist_rules(c) 비어 있지 않음                           → target=c,  detail {rules}
```
엔진은 **조건 목록만** 돌려준다. 열림·닫힘 상태는 Spring이 관리한다(§6.5).

## 1.11 리밸런싱 수량 (`domain/rebalance.py`)

| 알림 | 목표 비중 |
|---|---|
| R3, R4, R5 | 저장된 목표 비중 그대로 |
| R1, R2, R7 | 목표 비중에서 해당 종목(R1·R7) 또는 업종(R2)을 한도까지 깎는다(R7은 0까지). 남는 비중은 **다른 주식에 비례 배분**한다. 목표에 없이 직접 추가한 종목은 지금 비중을 그대로 두고, 깎은 비중을 받지도 않는다. 받을 주식이 없으면 현금 |
| R6 | 같은 `settings`로 `투자금 = total`, 기존 보유 없이 §1.8 전체를 새로 계산한 결과 |

```python
target_value = target_w × total
주식: target_qty = floor(target_value / close); trade = target_qty − qty (양수 BUY, 음수 SELL)
BOND: 금액 차이 → 단위 = 금액 / bond_idx ; CASH: 나머지
after = 거래 후 자산군 비중
```

## 1.12 백테스트 (`domain/backtest_engine.py`, `domain/metrics.py`)

### 공통
```python
rebal_dates = [첫 영업일 ≥ (y, 4, 1) for y in start_year..end.year if ≤ end]
month_ends  = 매달 마지막 영업일 (첫 리밸런싱 이후)
R = concat([returns, bond_ret→"BOND", 0→"CASH", kospi_ret→"KOSPI"]).loc[rebal_dates[0]:end].fillna(0)

def run_nav(targets: dict[date, Series], R, cost, init=100.0, band_check=None):
    days = R.index; W = targets[days[0]]; V = W * init; nav = [V.sum()]
    for d in days[1:]:
        V = V * (1 + R.loc[d, V.index])
        if d in targets: W = targets[d]; V = rebalance(V, W, cost)
        elif band_check and d in month_ends and band_check(V / V.sum(), W): V = rebalance(V, W, cost)
        nav.append(V.sum())
    return pd.Series(nav, index=days)

def rebalance(V, W, cost):
    idx = V.index.union(W.index); V = V.reindex(idx, fill_value=0); W = W.reindex(idx, fill_value=0)
    total = V.sum(); fee = (W * total - V).abs().sum() * cost
    return W * (total - fee)
```

### 실험별 목표 비중

| 실험 | 전략 키 | 각 리밸런싱일 t의 목표 |
|---|---|---|
| A | `VALUE` `QUALITY` `MOMENTUM` `STABILITY` `DIVIDEND` | 공통 필터(`use_managed=False`) → 업종 중립 점수 → 해당 팩터 점수 상위 15, EW |
| A | `MULTI_NEUTRAL` | 중립 기본 가중치(요청의 `multi_weights`) 종합 상위 15, EW |
| A | `MULTI_GLOBAL` | 같은 가중치, `neutral=False` 상위 15, EW |
| B | `MULTI_EW` `MULTI_IV` `MULTI_MV` | `MULTI_NEUTRAL` 종목, 1.8.2 방식(종목 한도 없음) |
| C | `MULTI_EW`, `KOSPI` | KOSPI는 `{"KOSPI": 1}` |
| D | 요청의 `profiles[].key` | **추천 로직 그대로**: 성향 필터 → 선택(비중만, 정수 주식·최소 금액 규칙·기존 보유 없음) → 기본 방식 → `allocate` → `{종목: s·w, BOND: b, CASH: c}`. 월말 `band_check` = R1~R4 조건 |
| D 기준 | `BENCH_{key}` | 같은 날 `{KOSPI: s, BOND: b, CASH: c}` (연 1회만) |

A~C는 연 1회만, D만 월말 점검을 한다. `MULTI_NEUTRAL`과 `MULTI_EW`는 같은 계산이므로 한 번만 계산한다.

구현 메모 (Claude 결정):
- D는 추천 계산(`recommend.compute`)을 그대로 부르고, 투자 금액을 1조 원으로 넣어 정수 주식·종목당 최소 금액 규칙이 걸리지 않게 한 뒤 비중만 꺼낸다. 성향 필터 뒤 후보가 0이면 그해는 현금 100%.
- D 월말 점검의 R1·R2는 "한도 초과 **이고** 목표보다 커졌을 때"만 참이다. 종목 수가 적어 목표 자체가 한도를 넘는 경우(1/N > 한도) 매달 거래하지 않게 하려는 것이다. R3·R4는 §1.10 그대로.
- 월말 = 매달 마지막 영업일. 데이터 마지막 날은 그 달이 끝났는지 모르므로 월말로 보지 않는다.
- 팩터 점수는 가중치와 무관하므로 날짜·업종중립 여부마다 한 번만 계산하고 순위만 가중치로 다시 매긴다(`scoring.score`와 같은 순서, 테스트로 확인).
- NAV는 날마다 numpy 배열로 굴린다(전략 하나 11년 0.04초). 2015~2026 A~D 전체(전략 27개)는 약 50초.

### 성과 지표 (`metrics.py`, 강의 `ret_risk_profile`)

| 키 | 공식 |
|---|---|
| `cumulative_return` | nav[-1]/nav[0] − 1 |
| `cagr` | (1+cum)^(1/years) − 1, years = 일수/365.25 |
| `volatility` | 주간(`resample("W").last()`) 수익률 std × √52 |
| `sharpe` | cagr / volatility |
| `downside_volatility` | 주간 음수 수익률 std × √52 |
| `sortino` | cagr / downside_volatility |
| `max_drawdown` | min(nav/cummax − 1) |
| `yearly` | 연말 nav 변화율 (첫 해는 첫날 대비) |
| `limit_breach_days` / `limit_breach_episodes` (D) | dd < loss_limit 인 날 수 / 연속 구간 수 |

### 과거 성과 카드
받은 `settings`로 D 로직을 `start_year=2015`부터 돌려 `cumulative_return`, `max_drawdown`, `worst_year`를 반환한다. `(settings_hash, data_version)`로 `profile_backtest_cache`에 저장한다.

## 1.13 배치 (`app/etl`)

### `load_base` — 제공 파일 → market 스키마

| 단계 | 하는 일 |
|---|---|
| 1 | `data/raw/` 확인: price_long.csv, price_sample.csv(cp949), financial.csv, sector.xlsx, managed.xlsx, macro.xlsx |
| 2 | 샘플 1·2행 → 이름→코드 (숫자 코드 6자리 0채움, 중복은 첫 번째) |
| 3 | 장기 가격: `.1` 열 버림, `KOSPI` 분리, **XKRX 세션 끝에서부터 날짜 복원** |
| 4 | 복원 검증: 샘플의 영업일 행과 겹치는 셀 차이 > 0.5가 있으면 중단 (`EtlValidationError`) |
| 5 | 재무: 코드 `A` 제거, 적용일 = (회계연도+1)-04-01, `source='FILE'` |
| 6 | 업종(문자열 6자리)·관리종목(정수 → 6자리), 업종 없으면 `미분류`, `sector_group` 부여 |
| 7 | `ktb_3y` → `rate_daily('KTB3Y')`, 채권 지수 → `bond_index_daily` |
| 8 | 한 트랜잭션으로 시장 테이블 비우고 적재(백테스트·캐시 테이블은 유지), `etl_log` 기록 |

### `update_market` — 최신 시세 이어 붙이기

```
days = XKRX 세션 (마지막 날짜, 어제]
날짜마다: 공공데이터포털 주식시세(KOSPI 전 종목) + 지수시세(코스피)
  처음 보는 코드 → stock 추가(업종 '미분류'), |전날 대비| > 0.30 → price_flag
ECOS 060Y001 / 010200000 (국고채 3년, 일) → rate_daily, 채권 지수 연장
한 트랜잭션 커밋, 실패 시 3회 재시도(1·2·4초) 후 중단
```
서비스명·파라미터 이름은 키 발급 후 활용가이드로 확인해 `clients/data_go_kr.py`에만 반영한다.

## 1.14 OpenDART 재무 보충 (`etl/fill_financials.py`)

| 열 | DART 계정 (account_id 우선, 없으면 account_nm) | 변환 |
|---|---|---|
| total_assets | `ifrs-full_Assets` / 자산총계 | 원 ÷ 1000 |
| total_liabilities | `ifrs-full_Liabilities` / 부채총계 | ÷1000 |
| total_equity | `ifrs-full_EquityAttributableToOwnersOfParent` → `ifrs-full_Equity` / 자본총계 | ÷1000 |
| capital | `ifrs-full_IssuedCapital` / 자본금 | ÷1000 |
| retained_earnings | `ifrs-full_RetainedEarnings` / 이익잉여금 | ÷1000 |
| revenue | `ifrs-full_Revenue` / 매출액 / 영업수익 / 수익(매출액) | ÷1000 |
| operating_income | `dart_OperatingIncomeLoss` / 영업이익 | ÷1000 |
| net_income | `ifrs-full_ProfitLossAttributableToOwnersOfParent` → `ifrs-full_ProfitLoss` | ÷1000 |
| dividends | `ifrs-full_DividendsPaidClassifiedAsFinancingActivities` / 배당금지급 | ÷1000, **음수로** |
| eps / bps / sps | 지배 순이익 / 지배 자본 / 매출(원) ÷ 주식 수 | 원 |
| ceps | (지배 순이익 + 감가상각비 + 무형자산상각비)(원) ÷ 주식 수 | 원 |

- 주식 수 = `stockTotqySttus` 보통주 발행주식 총수 − 자기주식
- 연결(CFS)을 우선하고, 없으면 별도(OFS)를 쓴다. 보고서는 사업보고서(11011)다.
- `--validate`: 기존 값이 있는 종목 중 코드 오름차순 50개의 2024 회계연도 EPS·BPS·SPS·CEPS를 계산해 상대오차 5% 이내 비율을 `data/reports/dart_validation_{날짜}.csv`로 남긴다. 하나라도 90% 미만이면 종료 코드 1을 내고, 채우기를 진행하지 않는다.
- 채우기: 기존 값이 빈 (종목, 2014~2025)만 `source='DART'`로 넣는다.

---

# §2. 공통 규약

## 2.1 규약 (공개 API, 내부 API 모두)

| 항목 | 규칙 |
|---|---|
| 형식 | JSON (CSV 내려받기만 `text/csv; charset=UTF-8`, BOM 포함) |
| 이름 | 공개 API는 camelCase(Spring 기본), 내부 API는 snake_case(Python 기본). quant-client DTO에 `@JsonNaming(SnakeCaseStrategy)` |
| 날짜 | `YYYY-MM-DD` / 일시 ISO 8601 UTC |
| 금액 | 정수(원) / 비율 소수(비중 4자리) / 결측 `null` |
| 가격 포함 응답 | `asOf`(`as_of`)와 `dataVersion`(`data_version`) 포함 |
| 요청 추적 | `X-Request-Id` (Spring 생성 → 엔진 전달) |

## 2.2 오류 형식

```json
{"error": {"code": "UNKNOWN_STOCK", "message": "없는 종목코드가 있어요.", "detail": {"codes": ["999999"]}}}
```
엔진도 같은 형식으로 돌려준다(`message`는 영어 개발용 문장). 사용자에게 보이는 한국어 `message`는 Spring이 코드별로 다시 채운다. 코드 목록은 02 §9.

---

# §3. 내부 API — Python `/internal/v1` (두 팀의 계약)

모든 요청에 `X-Service-Key`가 필요하다. 시장 데이터가 없으면 `/health`를 뺀 모든 API가 503 `DATA_NOT_LOADED`를 낸다.

| # | 메서드·경로 | 용도 | Spring 호출처 |
|---|---|---|---|
| I1 | GET `/health` | 상태, `data_loaded` | app(헬스체크) |
| I2 | GET `/meta/data-status` | 기간, as_of, data_version, 종목 수, 배치 시각 | web |
| I3 | GET `/meta/sectors` | 업종·그룹 목록 | profile(A7 선택지) |
| I4 | GET `/stocks` | 전 종목 목록 (검색은 Spring 캐시에서) | quant-client `StockCatalog` |
| I5 | GET `/stocks/{code}` | 종목 상세 (최신 종가, 상장폐지 위험 규칙) | web |
| I6 | POST `/recommendations/compute` | 추천 계산 (§1.5~1.9) | recommendation |
| I7 | POST `/history-card` | 과거 성과 카드 | recommendation |
| I8 | POST `/portfolio/evaluate` | 평가 + 알림 조건 (§1.10) | portfolio |
| I9 | POST `/portfolio/rebalance-plan` | 리밸런싱 수량 (§1.11) | alert |
| I10 | POST `/backtests` | 작업 생성 → 202 | backtest |
| I11 | GET `/backtests/{job_id}` | 상태·결과 요약 | backtest |
| I12 | GET `/backtests/{job_id}/nav?strategies=&freq=D` | NAV·낙폭 | backtest |
| I13 | GET `/backtests/{job_id}/holdings?strategy=&date=` | 편입 비중 | backtest |
| I14 | GET `/backtests/{job_id}/export.csv` | CSV | backtest |
| I15 | POST `/admin/reload` | 캐시 다시 읽기 | (운영자 수동) |

### I4. `GET /stocks`
```json
{"as_of": "2026-08-19", "data_version": 1,
 "items": [{"code": "005930", "name": "삼성전자", "sector": "전기·전자", "sector_group": "전기·전자",
            "is_managed": false, "last_close": 247500, "delist_rules": []}]}
```

### I6. `POST /recommendations/compute`
요청
```json
{
  "settings": {"grade": "NEUTRAL", "loss_limit": -0.10, "min_cash": 0.15, "stock_cap": 0.55, "n_stocks": 15,
               "factor_weights": {"VALUE": 0.2727, "QUALITY": 0.2727, "MOMENTUM": 0.2273, "STABILITY": 0.1364, "DIVIDEND": 0.0909},
               "vol_exclude_top_pct": 0.10, "exclude_overheat": true, "stock_limit": 0.20, "sector_limit": 0.40,
               "default_method": "INVERSE_VOL", "excluded_sectors": []},
  "invest_amount": 30000000,
  "existing": {"holdings": [{"code": "005930", "quantity": 20}], "bond_units": 0, "cash": 0},
  "edits": {"removed_codes": [], "safe_ratio": null, "method": null}
}
```
응답 200
```json
{
  "as_of": "2026-08-19", "data_version": 1, "bond_index": 102.59,
  "allocation": {"stock": 0.47, "bond": 0.38, "cash": 0.15,
                 "stock_amount": 14016900, "bond_amount": 11400000, "bond_units": 111122.9,
                 "cash_amount": 4583100, "cash_from_rounding": 83100,
                 "lookback": {"start": "2019-08-19", "end": "2026-08-19"}, "expected_mdd": -0.0987},
  "method_selected": "INVERSE_VOL",
  "methods": {
    "EQUAL":        {"fallback": false, "items": [{"code": "000270", "weight": 0.0667, "close": 112300, "amount": 940000, "shares": 8, "spend": 898400}]},
    "INVERSE_VOL":  {"fallback": false, "items": []},
    "MIN_VARIANCE": {"fallback": false, "items": []}
  },
  "stocks": [{"code": "000270", "name": "기아", "sector": "운송장비·부품", "group": "운송장비·부품", "group_n": 41,
              "rank_in_group": 2, "total_score": 0.8123,
              "factor_scores": {"VALUE": 0.91, "QUALITY": 0.88, "MOMENTUM": 0.55, "STABILITY": 0.62, "DIVIDEND": 0.80},
              "indicators": {"per": 4.1, "pbr": 0.71, "psr": 0.38, "pcr": 3.0, "roe": 0.173, "roa": 0.092,
                             "momentum_6m": 0.153, "vol_1y": 0.31, "rsi_14": 0.58, "debt_ratio": 0.85, "div_yield": 0.052},
              "is_held": false, "facts": {}}],
  "portfolio_facts": {},
  "screening": {},
  "combined": {"target_weights": {"005930": 0.141, "000270": 0.025, "BOND": 0.299, "CASH": 0.123},
               "by_asset": {"stock": 0.578, "bond": 0.299, "cash": 0.123},
               "by_sector": [{"sector": "전기·전자", "weight": 0.21}], "expected_mdd": -0.142},
  "warnings": [{"type": "COMBINED_LOSS_LIMIT", "detail": {"expected_mdd": -0.142, "loss_limit": -0.10}}]
}
```
`facts`, `portfolio_facts`, `screening`의 형식은 §1.9를 따른다. `methods`의 `items` 순서는 비중 내림차순 → 코드 순이다.

### I7. `POST /history-card`
```json
요청 {"settings": {}, "start_year": 2015}
응답 {"start": "2015-04-01", "end": "2026-08-19", "cumulative_return": 0.812, "max_drawdown": -0.093,
      "worst_year": {"year": 2022, "return": -0.071}, "cached": true, "data_version": 1}
```

### I8. `POST /portfolio/evaluate`
```json
요청
{"settings": {}, "holdings": [{"code": "005930", "quantity": 20}], "bond_units": 102345.6, "cash": 4320000,
 "targets": {"005930": 0.13, "BOND": 0.30, "CASH": 0.12},
 "purchased_at": "2026-08-19", "holdings_changed_at": "2026-08-19", "last_r6_year": null}
응답
{"as_of": "2026-08-19", "data_version": 1, "total_value": 35120000, "bond_index": 102.59,
 "assets": [{"asset": "005930", "quantity": 20, "close": 247500, "value": 4950000, "weight": 0.141, "weight_in_stock": 0.244,
             "sector": "전기·전자", "delist_rules": []},
            {"asset": "BOND", "units": 102345.6, "value": 10500000, "weight": 0.299}, {"asset": "CASH", "value": 4320000, "weight": 0.123}],
 "by_asset": {"stock": 0.578, "bond": 0.299, "cash": 0.123},
 "by_sector_in_stock": [{"sector": "전기·전자", "weight": 0.46}],
 "drawdown": -0.012,
 "conditions": [{"type": "R2", "target": "전기·전자", "detail": {"weight": 0.46, "limit": 0.40}}]}
```
- 종목은 평가금액 큰 순, 채권·현금은 끝. 그날 거래가 없던 종목은 직전 종가로 평가한다.
- `drawdown`: R5용 고점 대비 하락 (max(purchased_at, holdings_changed_at) 이후). 매수 전이면 null.
- R5 경계값(정확히 한도 × 0.8)도 알림이다. 부동소수 오차 때문에 1e-9 여유를 둔다.

### I9. `POST /portfolio/rebalance-plan`
```json
요청 (I8 요청 + ) {"alert_type": "R2", "alert_target": "전기·전자"}
응답 {"as_of": "2026-08-19", "data_version": 1, "bond_index": 152.21, "alert_type": "R2", "alert_target": "전기·전자",
      "total_value": 35120000, "target_weights": {"BOND": 0.30, "CASH": 0.13, "005930": 0.10},
      "trades": [{"asset": "005930", "side": "SELL", "quantity": 3, "close": 247500, "amount": 742500},
                 {"asset": "BOND", "side": "BUY", "amount": 120000, "units": 788.4},
                 {"asset": "000270", "side": "BUY", "quantity": 6, "close": 112300, "amount": 673800}],
      "before": {"by_asset": {"stock": 0.58, "bond": 0.29, "cash": 0.13}},
      "after": {"quantities": {"005930": 17, "000270": 6}, "bond_units": 69240.1, "cash": 4570000,
                "by_asset": {"stock": 0.57, "bond": 0.30, "cash": 0.13}, "weights": {"005930": 0.12, "BOND": 0.30, "CASH": 0.13}}}
```
- 거래는 파는 것 먼저, 금액 큰 순. 1주 단위 내림, 남는 돈은 현금. 거래 비용은 넣지 않는다. 평가금액이 0이면 409 `NOTHING_TO_REBALANCE`.

### I10~I14. 백테스트 작업
```json
I10 요청 {"start_year": 2015, "end_date": null, "trading_cost_rate": 0.003, "experiments": ["A","B","C","D"],
          "multi_weights": {"VALUE": 0.30, "QUALITY": 0.30, "MOMENTUM": 0.25, "STABILITY": 0.15, "DIVIDEND": 0},
          "profiles": [{"key": "STABLE_REP", "settings": {}}]}
I10 응답 202 {"job_id": "e9a0…", "status": "QUEUED"}
I11 응답 {"job_id": "e9a0…", "status": "DONE", "progress": 1.0, "error": null,
          "result": {"rebalance_dates": [], "experiments": {}, "strategies": [{"strategy": "STABLE_REP", "metrics": {}, "yearly": []}],
                     "limitations": ["SURVIVORSHIP", "NO_MANAGED_FILTER", "SYNTHETIC_BOND"], "elapsed_ms": 95000}}
```
`status`는 `QUEUED | RUNNING | DONE | FAILED`다. `progress`는 0~1(리밸런싱일 기준 진행률)이다.
작업은 엔진 프로세스 안의 작업자 스레드 하나가 차례로 돌린다. 엔진이 다시 켜지면 `QUEUED·RUNNING` 작업은 `FAILED`("엔진이 다시 시작돼 중단됐어요")로 바꾼다. 없는 작업이면 404 `BACKTEST_NOT_FOUND`, 실험 D인데 profiles가 없으면 422.
```json
I12 응답 {"freq": "W", "dates": ["2015-04-03", …], "strategies": {"KOSPI": {"nav": [100.0, …], "drawdown": [0.0, …]}}}
I13 응답 {"strategy": "MULTI_EW", "dates": ["2015-04-01", …], "date": "2025-04-01", "holdings": [{"asset": "005930", "weight": 0.0667}]}
I14 응답 text/csv: date,전략1,전략2,… (날마다 NAV)
I7  응답 {"start": "2015-04-01", "end": "2026-08-19", "cumulative_return": 0.1561, "max_drawdown": -0.1973,
          "worst_year": {"year": 2018, "return": -0.0825}, "data_version": 1, "cached": false}
```
- I12 `freq`가 W·M이면 기간마다 **실제 마지막 영업일**의 NAV를 쓰고, 낙폭은 그 기간의 최솟값이다. 영업일이 없는 주(긴 연휴)는 빠진다.
- I7 캐시 키는 (설정·시작 연도 해시, data_version). 계산 규칙을 바꾸면 `profile_backtest_cache`를 비운다.
- Spring은 I11~I14를 snake_case 그대로 전달한다 (§6.6). I13에는 종목명(`name`)을 붙인다.

---

# §4. DB — MySQL 8, `utf8mb4`

## 4.1 `market` 스키마 (Python 소유)

```sql
CREATE TABLE etl_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  job VARCHAR(20) NOT NULL,                       -- load_base | update_market | fill_financials
  started_at DATETIME(3) NOT NULL, finished_at DATETIME(3),
  status VARCHAR(10) NOT NULL,                    -- RUNNING | SUCCESS | FAILED
  steps JSON, message TEXT
);
CREATE TABLE trading_day (date DATE PRIMARY KEY);
CREATE TABLE stock (
  code VARCHAR(10) PRIMARY KEY, name VARCHAR(100) NOT NULL, market VARCHAR(10) NOT NULL DEFAULT 'KOSPI',
  sector VARCHAR(50) NOT NULL, sector_group VARCHAR(50) NOT NULL,
  is_managed TINYINT(1) NOT NULL DEFAULT 0, managed_date DATE, first_seen DATE
);
CREATE TABLE price_daily (
  code VARCHAR(10) NOT NULL, date DATE NOT NULL, close DOUBLE NOT NULL,
  PRIMARY KEY (code, date), KEY ix_price_date (date)
);
CREATE TABLE price_flag (code VARCHAR(10) NOT NULL, date DATE NOT NULL, change_ratio DOUBLE NOT NULL,
  checked TINYINT(1) NOT NULL DEFAULT 0, PRIMARY KEY (code, date));
CREATE TABLE index_daily (index_code VARCHAR(10) NOT NULL, date DATE NOT NULL, close DOUBLE NOT NULL,
  PRIMARY KEY (index_code, date));
CREATE TABLE rate_daily (series VARCHAR(10) NOT NULL, date DATE NOT NULL, value DOUBLE NOT NULL,
  PRIMARY KEY (series, date));
CREATE TABLE bond_index_daily (date DATE PRIMARY KEY, ret DOUBLE NOT NULL, idx DOUBLE NOT NULL);
CREATE TABLE financial_annual (
  code VARCHAR(10) NOT NULL, fiscal_year SMALLINT NOT NULL, settle_month TINYINT, apply_date DATE NOT NULL,
  total_assets DOUBLE, total_liabilities DOUBLE, total_equity DOUBLE, capital DOUBLE, retained_earnings DOUBLE,
  revenue DOUBLE, operating_income DOUBLE, net_income DOUBLE, dividends DOUBLE,        -- 천원
  eps DOUBLE, bps DOUBLE, sps DOUBLE, ceps DOUBLE,                                     -- 원
  source VARCHAR(5) NOT NULL,                                                          -- FILE | DART
  PRIMARY KEY (code, fiscal_year), KEY ix_fin_apply (apply_date)
);
CREATE TABLE backtest_run (
  id CHAR(36) PRIMARY KEY, status VARCHAR(10) NOT NULL, progress DOUBLE NOT NULL DEFAULT 0,
  params JSON NOT NULL, data_version BIGINT NOT NULL, rebalance_dates JSON, result_summary JSON,
  error TEXT, elapsed_ms INT, created_at DATETIME(3) NOT NULL, finished_at DATETIME(3)
);
CREATE TABLE backtest_nav (backtest_id CHAR(36) NOT NULL, strategy VARCHAR(40) NOT NULL, date DATE NOT NULL,
  nav DOUBLE NOT NULL, PRIMARY KEY (backtest_id, strategy, date));
CREATE TABLE backtest_metric (backtest_id CHAR(36) NOT NULL, strategy VARCHAR(40) NOT NULL, metric VARCHAR(40) NOT NULL,
  value DOUBLE, PRIMARY KEY (backtest_id, strategy, metric));
CREATE TABLE backtest_yearly (backtest_id CHAR(36) NOT NULL, strategy VARCHAR(40) NOT NULL, year SMALLINT NOT NULL,
  ret DOUBLE, PRIMARY KEY (backtest_id, strategy, year));
CREATE TABLE backtest_holding (backtest_id CHAR(36) NOT NULL, strategy VARCHAR(40) NOT NULL, date DATE NOT NULL,
  asset VARCHAR(10) NOT NULL, weight DOUBLE NOT NULL, PRIMARY KEY (backtest_id, strategy, date, asset));
CREATE TABLE profile_backtest_cache (settings_hash CHAR(64) NOT NULL, data_version BIGINT NOT NULL,
  result JSON NOT NULL, created_at DATETIME(3) NOT NULL, PRIMARY KEY (settings_hash, data_version));
```

## 4.2 `app` 스키마 (Spring 소유, Flyway `V1__init.sql`)

```sql
CREATE TABLE users (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, username VARCHAR(20) NOT NULL UNIQUE,
  password_hash VARCHAR(100) NOT NULL, created_at DATETIME(3) NOT NULL
);
CREATE TABLE profile (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, version INT NOT NULL,
  answers JSON NOT NULL, total_score INT NOT NULL, grade VARCHAR(12) NOT NULL, settings JSON NOT NULL,
  is_current TINYINT(1) NOT NULL DEFAULT 1, created_at DATETIME(3) NOT NULL,
  UNIQUE KEY ux_profile_ver (user_id, version),
  CONSTRAINT fk_profile_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE TABLE onboarding_state (
  user_id BIGINT PRIMARY KEY, pending_invest_amount BIGINT, completed_at DATETIME(3) NOT NULL,
  CONSTRAINT fk_onb_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE TABLE portfolio (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL UNIQUE,
  bond_units DOUBLE NOT NULL DEFAULT 0, cash BIGINT NOT NULL DEFAULT 0,
  purchased_at DATE, holdings_changed_at DATE NOT NULL, last_r6_year SMALLINT,
  created_at DATETIME(3) NOT NULL, version BIGINT NOT NULL DEFAULT 0          -- JPA 낙관적 락
);
CREATE TABLE holding (
  portfolio_id BIGINT NOT NULL, code VARCHAR(10) NOT NULL, quantity INT NOT NULL, avg_price DOUBLE,
  origin VARCHAR(10) NOT NULL,                                                  -- EXISTING | SERVICE
  PRIMARY KEY (portfolio_id, code),
  CONSTRAINT fk_holding_pf FOREIGN KEY (portfolio_id) REFERENCES portfolio(id) ON DELETE CASCADE,
  CONSTRAINT ck_qty CHECK (quantity > 0)
);
CREATE TABLE target_weight (
  portfolio_id BIGINT NOT NULL, asset VARCHAR(10) NOT NULL, weight DOUBLE NOT NULL, set_at DATETIME(3) NOT NULL,
  PRIMARY KEY (portfolio_id, asset),
  CONSTRAINT fk_tw_pf FOREIGN KEY (portfolio_id) REFERENCES portfolio(id) ON DELETE CASCADE
);
CREATE TABLE alert (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, portfolio_id BIGINT NOT NULL,
  type CHAR(2) NOT NULL, target VARCHAR(50) NOT NULL DEFAULT '',
  status VARCHAR(10) NOT NULL,                                                  -- OPEN | CLOSED | DISMISSED
  detail JSON NOT NULL, opened_at DATETIME(3) NOT NULL, closed_at DATETIME(3),
  active_key VARCHAR(80) GENERATED ALWAYS AS
    (CASE WHEN status IN ('OPEN','DISMISSED') THEN CONCAT(portfolio_id, ':', type, ':', target) END) VIRTUAL,                                  -- STORED면 ON DELETE CASCADE FK를 MySQL이 거부함
  UNIQUE KEY ux_alert_active (active_key),                                      -- 열린 알림은 하나만
  CONSTRAINT fk_alert_pf FOREIGN KEY (portfolio_id) REFERENCES portfolio(id) ON DELETE CASCADE
);
CREATE TABLE recommendation (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, profile_id BIGINT NOT NULL,
  data_version BIGINT NOT NULL, as_of DATE NOT NULL, invest_amount BIGINT NOT NULL,
  latest_version INT NOT NULL, purchased_version INT, created_at DATETIME(3) NOT NULL,
  KEY ix_rec_user (user_id, created_at),
  CONSTRAINT fk_rec_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE TABLE recommendation_version (
  recommendation_id BIGINT NOT NULL, version INT NOT NULL,
  edits JSON NOT NULL,                 -- {removedCodes, safeRatio, method}
  result JSON NOT NULL,                -- I6 응답 본문 그대로 (facts·screening 포함)
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (recommendation_id, version),
  CONSTRAINT fk_rv_rec FOREIGN KEY (recommendation_id) REFERENCES recommendation(id) ON DELETE CASCADE
);
CREATE TABLE backtest_job (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, job_id CHAR(36) NOT NULL UNIQUE,
  params JSON NOT NULL, last_status VARCHAR(10) NOT NULL, created_at DATETIME(3) NOT NULL,
  CONSTRAINT fk_bj_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
```

- `holding.code`에는 FK가 없다(종목은 market 스키마에 있음). 저장 전에 `StockCatalog`로 검증한다.
- 테이블은 모듈별로 소유한다. auth: users / profile: profile, onboarding_state / portfolio: portfolio, holding, target_weight / alert: alert / recommendation: recommendation, recommendation_version / backtest: backtest_job. **다른 모듈의 테이블을 리포지토리로 읽지 않는다.**

---

# §5. 설정 파일

## 5.1 `service/profile/src/main/resources/survey.yml` (Spring)

```yaml
optionScores: [5, 4, 2, 1, 0]
thresholds: { stableMax: 10, aggressiveMin: 25 }
grades:
  STABLE:     { label: 안정추구형, description: "약간의 리스크만을 허용하고, 물가수준 이상의 수익을 추구하는 투자자" }
  NEUTRAL:    { label: 위험중립형, description: "적당한 리스크를 허용하여 장기적으로 완만한 수익을 추구하는 투자자" }
  AGGRESSIVE: { label: 공격추구형, description: "금융시장의 큰 변동성을 감수하면서 고수익을 기대하는 투자자" }
questions:
  - { id: q1, text: "당신의 나이는?", options: ["44세 이하", "45~55세", "56~65세", "66~75세", "76세 이상"] }
  - { id: q2, text: "당신의 투자 기간은?", options: ["20년 이상", "11~19년", "6~10년", "1~5년", "1년 이내"] }
  - { id: q3, text: "당신이 우선 고려하는 투자는?", options: ["무조건 수익률 우선", "원금보전 고려하나 수익률 우선", "수익률과 원금보전 동일하게 고려", "수익률 고려하나 원금보전 우선", "어떤 경우라도 원금보전 우선"] }
  - { id: q4, text: "정상적인 주식시장 상황에서 이번 투자에서 기대하는 수익은?", options: ["시장 수익률은 상회해야 한다", "시장 수익률 수준은 따라가야 한다", "시장 수익률을 하회해도 평균적인 수익은 내야 한다", "어느 정도의 안정성과 평균적인 수익은 내야 한다", "안정성이 확보되면서 약간의 수익은 내야 한다"] }
  - { id: q5, text: "향후 10년간 주식시장 침체가 예상될 때 나의 투자성과는?", options: ["부진할 수 있다", "원금 수준은 유지해야 한다", "약간의 수익은 나야 한다", "꽤 괜찮은 수익을 기대한다", "어떤 경우라도 수익이 나야 한다"] }
  - { id: q6, text: "향후 3년 투자 시 투자성과에 대한 당신의 생각은?", options: ["원금 손실 가능성 인정", "대부분의 손실은 감내 가능", "약간의 손실은 감내 가능", "어떤 손실도 감내 어려움", "약간의 수익이라도 내야"] }
  - { id: q7, text: "향후 3개월 투자 시 투자손실에 대한 당신의 생각은?", options: ["시장변화에 크게 신경 쓰지 않는다", "20% 이상 손실 발생 시 걱정한다", "10% 이상 손실 발생 시 걱정한다", "작은 시장변화는 감내한다", "어떤 투자손실도 견디기 힘들다"] }
extra:
  - { id: a2, text: "이 돈 중 1년 안에 쓸 계획이 있는 금액은?", options: ["없음", "10% 이하", "10~30%", "30% 이상"] }
  - { id: a3, text: "이 돈과 별도로 비상금(생활비 3~6개월치)이 있나요?", options: ["있다", "없다"] }
  - { id: a4, text: "이 투자금이 전체 금융자산에서 차지하는 비중은?", options: ["20% 이하", "20~50%", "50~80%", "80% 이상"] }
  - { id: a5, text: "몇 종목까지 신경 쓸 수 있나요?", options: ["10개 안팎", "15개 안팎", "20개 이상", "상관없음"] }
  - { id: a6, text: "끌리는 투자 스타일은? (여러 개 선택)", multi: true,
      options: [{ key: VALUE, text: 싸게 사기 }, { key: QUALITY, text: 돈 잘 버는 회사 }, { key: MOMENTUM, text: 오르는 종목 },
                { key: STABILITY, text: 빚 적은 회사 }, { key: DIVIDEND, text: 배당 }, { key: ANY, text: 상관없음 }] }
  - { id: a7, text: "투자하고 싶지 않은 업종은? (선택 안 해도 돼요)", multi: true, optionsFrom: sectors }
```

## 5.2 `quant-engine/config/sector_groups.json` (Python)

```json
{"min_size": 10,
 "groups": {"금융": ["은행","보험","증권"], "유틸리티·통신": ["전기·가스","통신"], "헬스케어": ["제약","의료·정밀기기"],
            "소재 기타": ["비금속","종이·목재","기타제조"], "서비스": ["일반서비스","오락·문화"], "건설·부동산": ["건설","부동산"]}}
```

## 5.3 `service/backtest/src/main/resources/backtest-profiles.yml` (Spring, 실험 D 대표 프로필)

```yaml
profiles:
  - { key: STABLE_REP,     q: [4,4,4,4,4,4,4], a2: 2, a3: true, a4: 2, a5: 4, a6: [ANY], a7: [] }   # 7점 안정, -5%
  - { key: NEUTRAL_REP,    q: [3,3,3,3,3,3,3], a2: 2, a3: true, a4: 2, a5: 4, a6: [ANY], a7: [] }   # 14점 중립, -10%
  - { key: AGGRESSIVE_REP, q: [1,1,1,1,1,1,1], a2: 1, a3: true, a4: 1, a5: 4, a6: [ANY], a7: [] }   # 35점 공격, 제한 없음
  - { key: LIMIT_NONE,     q: [3,3,3,3,3,3,1], a2: 1, a3: true, a4: 1, a5: 4, a6: [ANY], a7: [] }   # 이하 모두 중립형
  - { key: LIMIT_20,       q: [3,3,3,3,3,3,2], a2: 1, a3: true, a4: 1, a5: 4, a6: [ANY], a7: [] }
  - { key: LIMIT_10,       q: [3,3,3,3,3,3,3], a2: 1, a3: true, a4: 1, a5: 4, a6: [ANY], a7: [] }
  - { key: LIMIT_05,       q: [3,3,3,3,3,3,4], a2: 1, a3: true, a4: 1, a5: 4, a6: [ANY], a7: [] }
  - { key: LIMIT_03,       q: [3,3,3,3,3,3,5], a2: 1, a3: true, a4: 1, a5: 4, a6: [ANY], a7: [] }
```
Spring이 §6.1로 설정값을 계산해 I10의 `profiles[].settings`로 보낸다.

## 5.4 `application.yml`의 `portfolio.profile` (Spring, 01 §5 소유=S)

```yaml
portfolio:
  min-invest-amount: 20000000
  profile:
    loss-limit-by-q7: [0, -0.20, -0.10, -0.05, -0.03]   # 0 = 제한 없음 (YAML 목록의 null은 바인딩 때 사라질 수 있음)
    cash-by-a2: [0, 0.10, 0.30, 0.50]
    cash-no-emergency-add: 0.05
    stock-cap: { STABLE: 0.40, NEUTRAL: 0.60, AGGRESSIVE: 0.90 }
    stock-cap-a4-adj: [0, 0, -0.05, -0.10]
    n-stocks-by-a5: [10, 15, 20, 15]
    stock-limit: { STABLE: 0.15, NEUTRAL: 0.20, AGGRESSIVE: 0.25 }
    sector-limit: { STABLE: 0.30, NEUTRAL: 0.40, AGGRESSIVE: 0.50 }
    default-method: { STABLE: MIN_VARIANCE, NEUTRAL: INVERSE_VOL, AGGRESSIVE: EQUAL }
    vol-exclude-top-pct: { STABLE: 0.30, NEUTRAL: 0.10, AGGRESSIVE: 0 }
    exclude-overheat: { STABLE: true, NEUTRAL: true, AGGRESSIVE: false }
    a6-bonus: 0.10
    factor-weights:
      STABLE:     { VALUE: 0.25, QUALITY: 0.30, MOMENTUM: 0.10, STABILITY: 0.25, DIVIDEND: 0.10 }
      NEUTRAL:    { VALUE: 0.30, QUALITY: 0.30, MOMENTUM: 0.25, STABILITY: 0.15, DIVIDEND: 0 }
      AGGRESSIVE: { VALUE: 0.25, QUALITY: 0.25, MOMENTUM: 0.45, STABILITY: 0.05, DIVIDEND: 0 }
quant:
  base-url: ${QUANT_BASE_URL:http://localhost:8000}
  service-key: ${QUANT_SERVICE_KEY}
  timeouts: { connect: 2s, default: 5s, compute: 15s, evaluate: 5s, history-card: 30s }
```

---

# §6. Spring 도메인

## 6.1 프로필 계산 (`profile` 모듈)

```java
public record Answers(List<Integer> q, int a2, boolean a3, int a4, int a5, List<String> a6, List<String> a7) {}

public record ProfileSettings(Grade grade, Double lossLimit, double minCash, double stockCap, int nStocks,
        Map<Factor, Double> factorWeights, double volExcludeTopPct, boolean excludeOverheat,
        double stockLimit, double sectorLimit, Method defaultMethod, List<String> excludedSectors) {}

int totalScore(List<Integer> q) { return q.stream().mapToInt(x -> cfg.optionScores().get(x - 1)).sum(); }
Grade grade(int s) { return s <= 10 ? STABLE : s >= 25 ? AGGRESSIVE : NEUTRAL; }

ProfileSettings settings(Answers a) {
    Grade g = grade(totalScore(a.q()));
    boolean shortHorizon = a.q().get(1) == 5;                       // Q2 "1년 이내"
    Grade filterGrade = shortHorizon ? STABLE : g;
    return new ProfileSettings(g,
        cfg.lossLimitByQ7().get(a.q().get(6) - 1),
        round2(cfg.cashByA2().get(a.a2() - 1) + (a.a3() ? 0 : cfg.cashNoEmergencyAdd())),
        round2(cfg.stockCap().get(g) + cfg.stockCapA4Adj().get(a.a4() - 1)),
        cfg.nStocksByA5().get(a.a5() - 1),
        factorWeights(g, a.a6()),
        cfg.volExcludeTopPct().get(filterGrade), cfg.excludeOverheat().get(filterGrade),
        cfg.stockLimit().get(g), cfg.sectorLimit().get(g), cfg.defaultMethod().get(g), a.a7());
}

Map<Factor, Double> factorWeights(Grade g, List<String> a6) {
    var w = new EnumMap<>(cfg.factorWeights().get(g));
    if (!a6.equals(List.of("ANY"))) a6.forEach(f -> w.merge(Factor.valueOf(f), cfg.a6Bonus(), Double::sum));
    double s = w.values().stream().mapToDouble(d -> d).sum();
    w.replaceAll((f, v) -> round4(v / s));
    return w;
}
```

**검증** (`INVALID_SURVEY` 422): q 7개 각 1~5 / a2·a4·a5 1~4 / a6 비어 있지 않음, `ANY`는 단독만, 나머지는 Factor 이름 / a7은 I3 업종 목록 안.

**버전**: 재진단하면 기존 `is_current=0`, 새 행 `version+1`로 저장하고 `ProfileChanged` 이벤트를 낸다.

## 6.2 추천 버전 (`recommendation` 모듈)

| 동작 | 처리 |
|---|---|
| 생성 | `investAmount`가 null이면 `onboarding_state.pending_invest_amount`(매수 완료 전 1회만). 추가 투자는 필수이며 20,000,000 이상이어야 한다. 엔진 I6 호출 → version 1 저장 |
| 수정 | 직전 edits에 누적한다. `removedCodes`는 합집합, `safeRatio`·`method`는 덮어쓴다. `null`은 유지, `safeRatio = -1`이면 해제. I6을 다시 호출해 version+1로 저장 |
| 조회 | 저장된 `result` + §6.3 렌더링. 엔진 호출 없음 |
| 왜 없나요 | 저장된 `result.screening[code]` → §6.3 사유 문구. 엔진 호출 없음. 코드가 없으면 `StockCatalog`로 확인 후 "최근 거래 가격이 없어요" |
| 과거 성과 카드 | 현재 프로필 settings로 I7 호출 (엔진이 캐시) |

## 6.3 문구 템플릿 (`recommendation/internal/render`, `messages_reasons.properties`)

### 종목 근거

| 사실 | 템플릿 |
|---|---|
| selection | `{group} {groupN}개 종목 중 {f1}·{f2} 점수가 높아요 (업종 안 {rankInGroup}위)` |
| level | `이익 대비 주가(PER) {per:0.0}배 · 자산 대비 주가(PBR) {pbr:0.00}배 · 자기자본이익률(ROE) {roe:%0.0}` |
| profile_link VALUE | `{gradeLabel}이라 싸게 거래되는 회사를 중요하게 봤어요. PER이 업종 상위 {pctTop:%0}예요` |
| profile_link QUALITY | `돈을 잘 버는 회사를 중요하게 봤어요. ROE가 업종 상위 {pctTop:%0}예요` |
| profile_link MOMENTUM | `최근 오르는 흐름을 중요하게 봤어요. 6개월 수익률이 {value:+%0.0}예요` |
| profile_link STABILITY | `{gradeLabel}이라 빚이 적은 회사를 중요하게 봤어요. 부채비율이 업종 상위 {pctTop:%0}예요` |
| profile_link DIVIDEND | `배당을 많이 주는 회사를 중요하게 봤어요. 배당수익률 {value:%0.0}예요` |
| caution HIGH_VOL | `최근 1년 변동성이 {vol1y:%0}로 큰 편이에요` |
| caution OVERHEAT | `단기 과열 신호가 있어요 (RSI {rsi14:0.00})` |
| caution WEAK_FACTOR | `{factorLabel} 점수는 업종 하위 30%예요` |
| role HELD / NO_HOLDINGS / DIVERSIFY / OVERLAP | `이미 갖고 계신 종목이에요` / `보유 종목이 없어 분산 효과는 따로 보지 않았어요` / `갖고 계신 종목에 없는 업종({sector})이라 분산에 도움이 돼요` / `갖고 계신 업종({sector})과 겹쳐요` |

### 포트폴리오 근거 (사실 + 프로필 답으로 만든다)

| 조건 | 템플릿 |
|---|---|
| 항상 | `현금 {cash:%0}: 1년 안에 쓸 돈이 '{a2Text}'라고 하셔서 바로 꺼낼 수 있게 남겼어요` (+ a3 없음: ` 비상금이 없다고 하셔서 5%p를 더 뒀어요`) |
| bond > 0 | `채권 {bond:%0}: 흔들림을 줄이려고 담았어요` |
| binding LOSS_LIMIT | `말씀하신 손실 한도({lossLimit:%0})를 넘지 않도록, 예측이 빗나갈 때를 대비해 여유를 두고 주식을 {stock:%0}로 정했어요. {startYear}년 이후 시장을 그대로 겪었다면 최대 하락폭은 {expectedMdd:%0.0}예요` (주식 0이면 `…에 여유를 두면 주식을 담기 어려워 채권과 현금으로만 나눴어요. …`) |
| binding CAP | `{gradeLabel}의 주식 상한 {stockCap:%0}에 맞췄어요` |
| binding USER | `직접 정하신 안전자산 비중을 따랐어요` (+ 경고 시 ` 말씀하신 손실 한도를 넘을 수 있어요`) |
| binding ALL_CASH | `말씀하신 손실 한도 안에서는 주식과 채권을 담기 어려워 현금으로 두었어요` |
| 항상 | `'{a5Text}' 관리할 수 있다고 하셔서 {nFinal}종목으로 나눴어요` (+ n_reduced: ` 주식 몫이 작아 종목당 50만 원 이상이 되도록 줄였어요`) |
| 항상 | `{gradeLabel}이라 '{methodLabel}' 방식을 기본으로 보여드려요` |
| skipped 있음 | `빠진 종목: ` + `{사유 라벨} {개수}개` 나열 |
| cash_from_rounding > 0 | `주식 수를 맞추고 남은 {cashFromRounding:#,###}원은 현금에 더했어요` |

### "왜 없나요" 사유

| 사유 | 문구 |
|---|---|
| NO_PRICE | 최근 거래 가격이 없어요 |
| DELIST_RISK | 상장폐지 위험 기준에 걸려요: {rules → D1 시가총액 500억 미만 / D2 매출 300억 미만 / D3 주가 1,000원 미만 / D4 자본잠식 50% 이상 / D5 관리종목} |
| NO_FINANCIAL | 최신 재무 자료가 없어요 |
| LOSS_MAKING | 최근 회계연도에 이익을 내지 못했어요 |
| INSUFFICIENT_HISTORY | 상장한 지 얼마 안 돼 비교할 자료가 부족해요 |
| SMALL_CAP | 시가총액이 후보 중 하위 10%예요 |
| HIGH_VOLATILITY | 변동성이 커서 {gradeLabel} 기준에서 뺐어요 (상위 {pct:%0}) |
| OVERHEATED | 최근 단기 급등(RSI {rsi14:0.00})이라 뺐어요 |
| EXCLUDED_SECTOR | 직접 제외하신 업종이에요 |
| USER_REMOVED | 직접 빼셨어요 |
| SECTOR_LIMIT | 같은 업종이 이미 한도({limit:%0})만큼 있어요 |
| PRICE_TOO_HIGH | 1주 가격({close:#,###}원)이 배정 금액보다 커요 |
| RANK_OUT | {group} {groupN}개 중 {rankInGroup}위라 순위 밖이에요 |

### 알림 문구

| 알림 | 문구 |
|---|---|
| R1 | `{name}이(가) 주식의 {weight:%0}예요 (한도 {limit:%0})` |
| R2 | `{target} 업종이 주식의 {weight:%0}예요 (한도 {limit:%0})` |
| R3 | `{assetName} 비중이 목표 {target:%0}에서 {current:%0}로 벌어졌어요` |
| R4 | `안전자산(채권+현금)이 목표 {target:%0}보다 줄어 {current:%0}예요` |
| R5 | `고점 대비 {drawdown:%0.0} 하락했어요. 말씀하신 한도 {limit:%0}에 가까워요` |
| R6 | `{year}년 정기 점검 시기예요. 새 재무가 반영됐어요` |
| R7 | `{name}이(가) 상장폐지 위험 기준에 걸렸어요: {rules}` |

포맷 기호: `%0` = 백분율 소수 0자리, `%0.0` = 1자리, `+%0.0` = 부호 포함, `#,###` = 천 단위 쉼표.

## 6.4 매수 완료 (`recommendation` → `portfolio.api`)

```
1. 요청 version이 latest_version이 아니거나, 이 추천이 사용자의 가장 최근 추천이 아니면 409 STALE_RECOMMENDATION. 이미 purchased_version 있으면 409 ALREADY_PURCHASED (수정도 같은 409)
2. result.methods[method_selected].items의 shares > 0인 종목:
     기존 수량 q0·평균가 p0, 새 수량 q1·종가 c → 수량 q0+q1, 평균가 (q0·p0 + q1·c)/(q0+q1), origin 유지(새 종목은 SERVICE)
3. bond_units += result.allocation.bond_units ; cash += result.allocation.cash_amount
4. target_weight 전부 교체 ← result.combined.target_weights
5. purchased_at = result.as_of ; holdings_changed_at = result.as_of
6. recommendation.purchased_version = version, onboarding_state.pending_invest_amount = null
7. PurchaseCompleted 이벤트
```
1~6은 한 트랜잭션이다(portfolio 모듈 파사드 메서드 하나로 처리).

## 6.5 알림 상태 (`alert` 모듈)

```
sync(portfolioId, conditions):          ← web이 GET·PATCH /api/portfolio 때마다 부른다 (alert는 portfolio에 의존, 반대는 아님)
  열린(OPEN·DISMISSED) 알림 중 conditions에 없는 것 → CLOSED, closed_at
     단 R6은 엔진이 그해 다시 내지 않으므로 자동으로 닫지 않는다 (사용자가 닫거나 반영할 때만)
  conditions 중 열린 알림이 없는 것 → OPEN 새로 저장 (detail = 조건 detail)
  열린 알림이 있는 것 → detail만 갱신 (상태 유지)
  R6가 새로 열리면 portfolio.last_r6_year = 올해 (portfolio.api 호출)
dismiss(alertId): OPEN → DISMISSED (조건이 사라졌다가 다시 생기면 새로 OPEN)
  R6은 바로 CLOSED (DISMISSED로 두면 active_key가 남아 다음 해 R6를 못 연다). 닫힌 알림이면 409 ALERT_CLOSED
suggestion(alertId): I9 호출 → 표에 종목명·문구를 붙여 반환
apply(alertId): I9를 다시 호출해(가격·보유가 바뀌었을 수 있음) after 값으로 포트폴리오를 바꾸고 알림 CLOSED
  수량·채권 단위·현금 = 거래 후 값, 더 산 종목은 평균가 재계산, 판 종목은 평균가 유지,
  목표 비중 = I9의 target_weights (R1·R2·R7은 깎은 비중, R6은 새로 짠 비중), holdings_changed_at = as_of
```
- 알림 문구는 `alert/src/main/resources/messages_alerts.properties`. 틀 채우기는 추천 근거와 같은 `common.Texts`를 쓴다.

## 6.6 백테스트 작업 (`backtest` 모듈)

```
create(userId, params):
  profiles = backtest-profiles.yml 각 항목 → profile.api.calculateSettings(answers)
  multiWeights = application.yml factor-weights.NEUTRAL
  job = I10 호출 → backtest_job(user_id, job_id, params, last_status='QUEUED')
get(userId, jobId): 소유자 확인(아니면 404) → I11 → last_status 갱신 → 전달
nav/holdings/export: 소유자 확인 → I12~I14 그대로 전달
```

---

# §7. 공개 API (Spring) + 화면

## 7.1 화면 경로 (Thymeleaf)

| 경로 | 설명 | 접근 조건 |
|---|---|---|
| `GET /` | 로그인 상태에 따라 `/onboarding`, `/recommendations/{최근}`, `/portfolio`로 이동 | |
| `GET/POST /signup`, `GET/POST /login`, `POST /logout` | 폼 | 공개 |
| `GET /onboarding` | 단계형 설문 | 온보딩 전 |
| `GET /recommendations/{id}` | 추천 결과 | 본인 |
| `GET /portfolio` | 내 포트폴리오 | 매수 완료 후 |
| `GET /alerts/{id}` | 리밸런싱 제안 | 본인 |
| `GET /backtests`, `GET /backtests/{jobId}` | 백테스트 | 로그인 |
| `GET /profile/survey` | 재진단 | 온보딩 후 |

## 7.2 JSON API (`/api`, 세션 + CSRF)

| # | 메서드·경로 | 설명 | 요구사항 |
|---|---|---|---|
| 1 | GET `/api/me` | 사용자, 온보딩·포트폴리오 여부 | — |
| 2 | GET `/api/meta/data-status` | I2 전달 | FR-META-01 |
| 3 | GET `/api/sectors` | I3 전달 | FR-META-04 |
| 4 | GET `/api/stocks?q=&sector=&limit=20` | `StockCatalog` 검색 (이름 부분일치·코드 앞자리) | FR-META-02 |
| 5 | GET `/api/stocks/{code}` | I5 전달 + 상장폐지 사유 문구 | FR-META-03 |
| 6 | GET `/api/onboarding/survey` | survey.yml + 업종 목록 | FR-ONB-01 |
| 7 | POST `/api/onboarding` | 온보딩 제출 | FR-ONB-02~09·11 |
| 8 | GET `/api/profile` | 현재 프로필 | — |
| 9 | PUT `/api/profile/survey` | 재진단 | FR-ONB-10 |
| 10 | POST `/api/recommendations` | 추천 생성 | FR-REC-01~10, FR-PF-05 |
| 11 | GET `/api/recommendations/{id}?version=` | 조회 | — |
| 12 | POST `/api/recommendations/{id}/versions` | 수정 | FR-REC-12·13 |
| 13 | GET `/api/recommendations/{id}/why-not/{code}` | 왜 없나요 | FR-REC-11 |
| 14 | GET `/api/recommendations/{id}/history-card` | 과거 성과 카드 | FR-REC-14 |
| 15 | POST `/api/recommendations/{id}/purchase` | 매수 완료 | FR-PF-01 |
| 16 | GET `/api/portfolio` | 평가 + 알림 | FR-PF-02, FR-ALT-01 |
| 17 | GET `/api/portfolio/stocks/{code}` | 종목 상세 | FR-PF-03 |
| 18 | PATCH `/api/portfolio` | 수량·채권·현금 수정 | FR-PF-04 |
| 19 | GET `/api/alerts?status=OPEN` | 알림 목록 | FR-ALT-* |
| 20 | POST `/api/alerts/{id}/dismiss` | 닫기 | FR-ALT-08 |
| 21 | GET `/api/alerts/{id}/suggestion` | 리밸런싱 수량 | FR-ALT-09 |
| 21a | POST `/api/alerts/{id}/apply` | 제안대로 했다고 기록 (포트폴리오 반영, 알림 닫힘) | FR-ALT-09 |
| 22 | POST `/api/backtests` | 작업 생성 → 202 | FR-BT-01 |
| 23 | GET `/api/backtests` | 내 작업 목록 | — |
| 24 | GET `/api/backtests/{jobId}` | 상태·결과 | FR-BT-07·10 |
| 25 | GET `/api/backtests/{jobId}/nav`, `/holdings`, `/export.csv` | 전달 | FR-BT-11 |

### 7.3 주요 요청·응답

**7. `POST /api/onboarding`** → 201
```json
요청 {"investAmount": 30000000, "q": [3,3,3,3,3,3,3], "a2": 2, "a3": false, "a4": 3, "a5": 4,
      "a6": ["DIVIDEND"], "a7": [], "holdings": [{"code": "005930", "quantity": 20}]}
응답 {"profileId": 1, "version": 1, "totalScore": 14, "grade": "NEUTRAL", "gradeLabel": "위험중립형",
      "gradeDescription": "적당한 리스크를 허용하여 장기적으로 완만한 수익을 추구하는 투자자",
      "settings": {"lossLimit": -0.10, "minCash": 0.15, "stockCap": 0.55, "nStocks": 15,
                   "factorWeights": {"VALUE": 0.2727, "QUALITY": 0.2727, "MOMENTUM": 0.2273, "STABILITY": 0.1364, "DIVIDEND": 0.0909},
                   "volExcludeTopPct": 0.10, "excludeOverheat": true, "stockLimit": 0.20, "sectorLimit": 0.40,
                   "defaultMethod": "INVERSE_VOL", "excludedSectors": []},
      "warnings": []}
```
`investAmount` < 20,000,000이면 422 `VALIDATION_ERROR`, 없는 종목이면 422 `UNKNOWN_STOCK`, 이미 온보딩했으면 409 `ALREADY_ONBOARDED`. A2가 4이면 `warnings`에 `CASH_NEED_HIGH`를 넣는다.

**10·11·12. 추천** → I6 응답을 camelCase로 바꾸고 아래를 덧붙인다.
```json
{"recommendationId": 7, "version": 1, "purchased": false,
 "allocationReasons": ["현금 15%: 1년 안에 쓸 돈이 '10% 이하'라고 하셔서 …", "채권 38%: 흔들림을 줄이려고 담았어요", "…"],
 "stocks": [{"code": "000270", "name": "기아", "reasons": [{"type": "SELECTION", "text": "운송장비·부품 41개 종목 중 가치·수익성 점수가 높아요 (업종 안 2위)"}]}],
 "edits": {"removedCodes": [], "safeRatio": null, "method": null}}
```
`screening`은 응답에서 뺀다(너무 크다). "왜 없나요" API로만 쓴다.

**13. why-not**
```json
{"code": "005930", "name": "삼성전자", "inPortfolio": false, "reason": "HIGH_VOLATILITY",
 "text": "변동성이 커서 위험중립형 기준에서 뺐어요 (상위 4%)"}
```

**16. `GET /api/portfolio`** → I8 응답 + 종목명·평균가·수익률·목표 비중
```json
{"asOf": "2026-08-19", "dataVersion": 1, "totalValue": 35120000, "bondIndex": 152.21, "drawdown": 0.0,
 "purchased": true, "purchasedAt": "2026-08-19", "holdingsChangedAt": "2026-08-19",
 "byAsset": {"stock": 0.578, "bond": 0.299, "cash": 0.123},
 "targetByAsset": {"stock": 0.57, "bond": 0.30, "cash": 0.13},
 "stocks": [{"code": "005930", "name": "삼성전자", "sector": "전기·전자", "quantity": 20, "close": 247500, "value": 4950000,
             "weight": 0.141, "weightInStock": 0.244, "targetWeight": 0.13, "avgPrice": 230000, "returnRate": 0.0761,
             "origin": "EXISTING", "delistRules": []}],
 "bond": {"units": 102345.6, "value": 10500000, "weight": 0.299, "targetWeight": 0.30},
 "cash": {"value": 4320000, "weight": 0.123, "targetWeight": 0.13},
 "targetOnly": [{"code": "000270", "name": "기아", "targetWeight": 0.02}],
 "bySectorInStock": [{"sector": "전기·전자", "weight": 0.46}],
 "conditions": [{"type": "R2", "target": "전기·전자", "detail": {"weight": 0.46, "limit": 0.40}}],
 "limits": {"stockLimit": 0.20, "sectorLimit": 0.40, "lossLimit": -0.10}}
```
- `avgPrice`가 null이면 평균 매입가를 모른다(온보딩 때 넣은 보유). 이때 `returnRate`도 null.
- `targetOnly`: 목표 비중에는 있지만 지금은 없는 종목 (직접 판 경우).
- `conditions`는 엔진 판정 그대로다. 알림 상태(열림·닫힘)와 문구(`alerts`)는 alert 모듈이 붙인다(§6.5).
- 엔진이 응답하지 않으면 503 `QUANT_UNAVAILABLE`.
- 매수 완료 전이면 화면 `/portfolio`는 `/`로 보낸다.

**18. `PATCH /api/portfolio`**
```json
{"holdings": [{"code": "005930", "quantity": 25}, {"code": "000660", "quantity": 0}], "bondAmount": 10000000, "cash": 5000000}
```
수량 0이면 삭제한다. 없던 종목은 `EXISTING`으로 추가한다. 종목마다 `avgPrice`(원)를 함께 보낼 수 있고, 0이면 '모름'으로 되돌린다.
`bondAmount`는 I8의 `bond_index`로 단위로 바꿔 저장한다. `holdings_changed_at`을 시장 데이터 기준일로 바꾸고 `HoldingsChanged` 이벤트를 낸다.
응답은 수정 후의 #16과 같다.

---

# §8. 테스트 케이스

## 8.1 Python — 채권

| ID | 입력 | 기대 |
|---|---|---|
| T-BOND-01 | 금리 3.00% → 3.10% | −0.00268095 |
| T-BOND-02 | 3.00% 유지 | 0.000119048 |
| T-BOND-03 | 실제 2019-08-19~2026-08-19 | MDD ≈ −0.063 (±0.005) |

## 8.2 Python — 지표·거르기

- **T-IND-01 골든** (§1.5), 소수 6자리
- **T-IND-02 미래 정보**: `snapshot(2026-03-31).fin` → FY2024, `snapshot(2026-04-01)` → FY2025, `close_hist.index.max() == t`
- **T-SCR-01 상장폐지**: 원본 데이터 2026-08-19(재무 보충 전) D1 50 / D2 11 / D3 31 / D4 7 / D5 35, 하나 이상 **91**
- **T-SCR-02 순서**: 종가 없음 + 적자 → `NO_PRICE`
- **T-SCR-03 소형주**: 남은 20개 → 하위 2개 `SMALL_CAP`

## 8.3 Python — 업종 중립 점수 (`min_size=2`, VALUE=1, PER만)

| code | 그룹 | PER |
|---|---|---|
| A | G1 | 5 |
| B | G1 | 10 |
| C | G2 | 20 |
| D | G2 | 40 |

- 중립: A 0.5, B 0, C 0.5, D 0 → **A, C, B, D**
- 전체 순위: 0.75, 0.5, 0.25, 0 → **A, B, C, D**
- `min_size=3` → 둘 다 `__ALL__` → 전체 순위와 같음

## 8.4 Python — 선택·비중·배분·주문

| ID | 입력 | 기대 |
|---|---|---|
| T-SEL-01 | budget 1,000,000, n 15 | n → 2 |
| T-SEL-02 | per 700,000, 1주 1,500,000 | `PRICE_TOO_HIGH` |
| T-W-01 | cap_weights([0.5, 0.3, 0.2], 0.4) | [0.4, 0.36, 0.24] |
| T-W-02 | IV, vol [0.1, 0.2] | [0.6667, 0.3333] |
| T-W-03 | 5종목, stock_limit 0.15 | cap 0.2, EW 0.2씩 |
| T-ALC-01 | rS [−0.20, 0, 0], rB [0, 0, 0], L −0.10, c_min 0, cap 0.9 | s 0.50, b 0.50, binding LOSS_LIMIT |
| T-ALC-02 | rS [−0.20, 0, 0], rB [−0.06, 0, 0], L −0.05 | s 0.25, b 0.00, **c 0.75** |
| T-ALC-03 | L None, c_min 0.15, cap 0.55 | s 0.55, b 0.30, binding CAP |
| T-ORD-01 | 1,000,000, s 1, w [0.5, 0.5], close [300,000, 70,000] | shares [1, 7], spend [300,000, 490,000], cash_from_rounding 210,000 |

## 8.5 Python — 알림 조건·리밸런싱

| ID | 상황 | 기대 |
|---|---|---|
| T-ALT-01 | 주식 안 비중 0.21, stock_limit 0.20 | R1 |
| T-ALT-02 | 목표 BOND 0.30, 현재 0.24 | R3(BOND) |
| T-ALT-03 | loss_limit −0.10, dd −0.08 | R5 |
| T-ALT-04 | loss_limit None | R5 없음 |
| T-ALT-05 | 4/1 이전 매수, 4월 첫 영업일 이후, last_r6_year < 올해 | R6 |
| T-REB-01 | R1 종목 0.25 → 한도 0.20 | 줄인 0.05가 다른 주식에 비례 배분, 채권 비중 그대로 |

## 8.6 Python — 백테스트·지표

| ID | 입력 | 기대 |
|---|---|---|
| T-BT-01 | 리밸런싱일 2015~2026 | 2015-04-01, 2016-04-01, 2017-04-03, 2018-04-02, 2019-04-01, 2020-04-01, 2021-04-01, 2022-04-01, 2023-04-03, 2024-04-01, 2025-04-01, 2026-04-01 |
| T-BT-02 | 2자산 W [0.5, 0.5] d0·d2, 수익률 d1 [0.10, 0], 비용 0.01 | nav 100, 105, **104.95** |
| T-BT-03 | 월말 band_check 참 | 그날 재조정·비용 |
| T-MET-01 | nav [100, 120, 90, 130] | MDD −0.25 |
| T-MET-02 | 2020-01-01 100 → 2022-01-01 121 | CAGR 0.099928 |
| T-MET-03 | 한도 −0.10, dd [0, −0.12, −0.11, −0.05, −0.13] | breach_days 3, episodes 2 |
| T-BT-04 | 같은 파라미터 2회 | 지표 동일 |

## 8.7 Spring — 단위

| ID | 대상 | 기대 |
|---|---|---|
| T-SP-PRO-01 | 01 FR-ONB 골든 프로필 | lossLimit −0.10, minCash 0.15, stockCap 0.55, nStocks 15, 가중치 (0.2727, 0.2727, 0.2273, 0.1364, 0.0909) |
| T-SP-PRO-02 | q 모두 1 / 3 / 5 | 35 AGGRESSIVE / 14 NEUTRAL / 0 STABLE |
| T-SP-PRO-03 | grade(10/11/24/25) | STABLE / NEUTRAL / NEUTRAL / AGGRESSIVE |
| T-SP-PRO-04 | q2=5, AGGRESSIVE | volExcludeTopPct 0.30, excludeOverheat true |
| T-SP-PRO-05 | a6 ["ANY","VALUE"] | 422 INVALID_SURVEY |
| T-SP-REN-01 | selection 사실 예시(§1.9) | "운송장비·부품 41개 종목 중 가치·수익성 점수가 높아요 (업종 안 2위)" |
| T-SP-REN-02 | binding LOSS_LIMIT, L −0.10, s 0.47, mdd −0.0987, lookback.start 2010-01-04 | "말씀하신 손실 한도(-10%)를 넘지 않도록, 예측이 빗나갈 때를 대비해 여유를 두고 주식을 47%로 정했어요. 2010년 이후 시장을 그대로 겪었다면 최대 하락폭은 -9.9%예요" |
| T-SP-VER-01 | v1 removed [A] → v2 removed [B] | v2 edits.removedCodes [A, B] |
| T-SP-BUY-01 | 기존 20주 @230,000 + 새 5주 @247,500 | 25주, 평균가 233,500 |
| T-SP-ALT-01 | 열린 R2 + 조건에 R2 없음 | R2 CLOSED |
| T-SP-ALT-02 | 같은 조건 2회 sync | 열린 알림 1건 |
| T-SP-MOD-01 | `ApplicationModules.of(App.class).verify()` | 통과 (모듈 경계 위반 없음) |

## 8.8 계약 테스트

- Spring `quant-client` 테스트는 §3의 예시 JSON을 WireMock 응답으로 쓰고, 역직렬화가 되는지 확인한다.
- Python API 테스트는 같은 예시 JSON을 요청으로 보내 200이 나오는지 확인한다.
- 예시 JSON은 `docs/contracts/*.json`에 두고 두 팀이 같은 파일을 쓴다.

## 8.9 통합 시나리오 (docker-compose, 실제 엔진)

| ID | 시나리오 | 기대 |
|---|---|---|
| T-INT-01 | 가입 → 로그인 → 온보딩(골든 프로필) | 201, settings 일치 |
| T-INT-02 | 온보딩 금액 19,999,999 | 422 |
| T-INT-03 | 추천 → 같은 요청 다시 | 종목·비중 동일 |
| T-INT-04 | 추천 종목에 상장폐지 위험·관리종목 없음 | 통과 |
| T-INT-05 | removedCodes=[1위] | 1위 빠지고 다음 순위 들어옴, version 2 |
| T-INT-06 | safeRatio 0.1 | 201 + LOSS_LIMIT_EXCEEDED |
| T-INT-07 | why-not 삼성전자 | 사유 1개와 문구 |
| T-INT-08 | 옛 버전 purchase | 409 STALE_RECOMMENDATION |
| T-INT-09 | purchase → 포트폴리오 | 수량 합산, 목표 비중 저장, asOf 포함 |
| T-INT-10 | 다른 사용자 추천 조회 | 404 |
| T-INT-11 | 엔진 중지 후 `/api/portfolio` | 503 QUANT_UNAVAILABLE, 추천 조회(`/api/recommendations/{id}`)는 200 |
| T-INT-12 | 백테스트 생성 → 폴링 → DONE | experiments A~D, rebalance_dates 12개 |
| T-INT-13 | 서비스 키 없이 엔진 직접 호출 | 401 |
