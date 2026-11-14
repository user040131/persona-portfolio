"""추천 계산 서비스 (I6). 최신 영업일 지표·공통 필터는 데이터 버전마다 한 번만 계산해 둔다."""
from __future__ import annotations

from app.config import Settings
from app.data.market_data import MarketData
from app.domain import indicators, recommend, screening
from app.domain.types import Edits, Existing, ProfileSettings, RecoParams


def reco_params(s: Settings) -> RecoParams:
    return RecoParams(min_amount_per_stock=s.min_amount_per_stock, rsi_overheat=s.rsi_overheat,
                      sector_min_size=s.sector_min_size, mdd_lookback_years=s.mdd_lookback_years,
                      mdd_min_years=s.mdd_min_years, vol_lookback=s.vol_lookback, cov_min_obs=s.cov_min_obs,
                      loss_limit_margin=s.loss_limit_margin, bond_shock_loss=s.bond_rate_shock * s.bond_duration)


def latest_inputs(md: MarketData, s: Settings) -> recommend.Inputs:
    def build() -> recommend.Inputs:
        snap = md.snapshot()
        ind = indicators.compute(snap, indicators.IndicatorParams(s.momentum_lookback, s.vol_lookback,
                                                                  s.vol_min_obs, s.rsi_period))
        common = screening.screen_common(ind, snap, screening.ScreenParams(
            screening.DelistParams(s.delist_mcap_min, s.delist_revenue_min, s.delist_price_min, s.delist_impairment),
            s.mcap_bottom_pct, use_managed=True))
        return recommend.Inputs(snap, ind, common, float(md.bond_idx.loc[snap.date]), md.data_version)

    return md.memo(("latest_inputs",), build)


def compute(md: MarketData, s: Settings, settings: ProfileSettings, invest_amount: int, existing: Existing,
            edits: Edits) -> dict:
    return recommend.compute(latest_inputs(md, s), settings, invest_amount, existing, edits, reco_params(s))
