"""엔진 설정 (docs/02 §7). 환경변수로 덮어쓸 수 있다.

계산 파라미터는 docs/01 §5에서 소유가 P인 값만 둔다. 바꿀 때는 01 §5 표와 함께 바꾼다.
프로필 매핑값(손실 한도·현금·주식 상한 등)은 Spring이 계산해 ProfileSettings로 보내므로 여기 없다.
"""
from __future__ import annotations

from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

BASE_DIR = Path(__file__).resolve().parent.parent  # quant-engine/


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=BASE_DIR / ".env", env_file_encoding="utf-8", extra="ignore")

    # ---- 연결 ----
    database_url: str = "mysql+pymysql://quant_user:quant_pw@localhost:3307/market?charset=utf8mb4"
    quant_service_key: str = "dev-only-change-me"   # Spring의 quant.service-key와 같아야 한다

    # ---- 배치용 무료 인증키 (docs/02 §7.2) ----
    data_go_kr_key: str | None = None
    ecos_key: str | None = None
    dart_key: str | None = None

    # ---- 경로 ----
    raw_dir: Path = BASE_DIR / "data" / "raw"
    reports_dir: Path = BASE_DIR / "data" / "reports"
    cache_dir: Path | None = BASE_DIR / "data" / "cache"   # 시장 데이터 피클 (data_version별). None이면 안 씀
    load_market_on_startup: bool = True
    config_dir: Path = BASE_DIR / "config"

    # ---- 계산 파라미터 (01 §5, 소유=P) ----
    min_amount_per_stock: int = 500_000
    rsi_overheat: float = 0.70
    momentum_lookback: int = 120
    vol_lookback: int = 252
    vol_min_obs: int = 200
    cov_min_obs: int = 120
    rsi_period: int = 14
    sector_min_size: int = 10
    mcap_bottom_pct: float = 0.10
    delist_mcap_min: float = 5e10
    delist_revenue_min: float = 3e10
    delist_price_min: float = 1_000
    delist_impairment: float = 0.5
    mdd_lookback_years: int = 30            # 사실상 자료 전부(2010~). 7년은 코로나·2022 금리 인상 같은 충격을 놓쳤다 (백테스트 D 분석)
    loss_limit_margin: float = 0.7          # 예상 하락이 한도의 70% 안이 되게 고른다 (예측이 빗나갈 때 대비)
    bond_rate_shock: float = 0.02           # 금리 +2%p 충격(× 채권 듀레이션)에도 채권 손실이 한도 안이어야 한다
    mdd_min_years: int = 5
    allocation_grid_step: float = 0.01
    bond_duration: float = 2.8
    rebalance_band: float = 0.05
    loss_alert_ratio: float = 0.8
    trading_cost_rate: float = 0.003
    backtest_start_year: int = 2015
    fin_apply_month: int = 4
    fin_apply_day: int = 1
    price_jump_flag: float = 0.30


@lru_cache
def get_settings() -> Settings:
    return Settings()
