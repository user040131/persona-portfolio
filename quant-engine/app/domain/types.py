"""도메인 공통 타입. domain 패키지는 DB·HTTP를 모르므로 데이터 모양만 여기서 정한다."""
from __future__ import annotations

from dataclasses import dataclass, field

import pandas as pd

FACTOR_ORDER = ("VALUE", "QUALITY", "MOMENTUM", "STABILITY", "DIVIDEND")
METHODS = ("EQUAL", "INVERSE_VOL", "MIN_VARIANCE")


@dataclass(frozen=True)
class Snapshot:
    """기준일 t 시점에 알 수 있는 것만 담는다 (미래 정보 차단, NFR-03)."""
    date: pd.Timestamp
    close_hist: pd.DataFrame       # index ≤ t, columns=종목코드
    returns_hist: pd.DataFrame
    kospi_ret_hist: pd.Series
    bond_ret_hist: pd.Series
    fin: pd.DataFrame              # index=code, 기대 회계연도 행만
    stocks: pd.DataFrame           # index=code (name, sector, sector_group, is_managed)


@dataclass(frozen=True)
class ProfileSettings:
    """Spring이 설문으로 계산해 보내는 설정값 (docs/03 §1.2). 엔진은 설문 답을 모른다."""
    grade: str
    loss_limit: float | None
    min_cash: float
    stock_cap: float
    n_stocks: int
    factor_weights: dict[str, float]
    vol_exclude_top_pct: float
    exclude_overheat: bool
    stock_limit: float
    sector_limit: float
    default_method: str
    excluded_sectors: tuple[str, ...] = ()


@dataclass(frozen=True)
class Edits:
    """추천 결과 수정 (docs/03 §1.8.5)."""
    removed_codes: tuple[str, ...] = ()
    safe_ratio: float | None = None
    method: str | None = None


@dataclass(frozen=True)
class Existing:
    """기존 보유 (서비스 이전부터 갖고 있던 것 + 매수 완료분)."""
    holdings: dict[str, int] = field(default_factory=dict)    # 종목코드 → 수량
    bond_units: float = 0.0
    cash: int = 0


@dataclass(frozen=True)
class RecoParams:
    """추천 계산 파라미터 (01 §5 소유=P)."""
    min_amount_per_stock: int = 500_000
    rsi_overheat: float = 0.70
    sector_min_size: int = 10
    mdd_lookback_years: int = 30
    mdd_min_years: int = 5
    loss_limit_margin: float = 0.7
    bond_shock_loss: float = 0.056          # 금리 +2%p × 듀레이션 2.8
    vol_lookback: int = 252
    cov_min_obs: int = 120
    high_vol_caution_pct: float = 0.20     # 근거 '주의할 점: 변동성 큼' 기준 (결정 7)
    weak_factor_score: float = 0.30        # 근거 '업종 하위 30%' 기준
    max_rounds: int = 5                    # 주식 몫 ↔ 종목 선택 반복 (결정 10-8은 3회였으나 수렴 여유를 둠)
