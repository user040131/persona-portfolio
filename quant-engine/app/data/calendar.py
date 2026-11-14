"""한국거래소 영업일 (exchange_calendars XKRX, 강의 `주가데이터_비영업일처리`)."""
from __future__ import annotations

from functools import lru_cache

import exchange_calendars as xcals
import pandas as pd

# 기본값(오늘부터 20년 전)은 시간이 지나면 앞부분이 잘리므로 시작을 고정한다
CALENDAR_START = "2005-01-03"


@lru_cache(maxsize=1)
def _xkrx() -> xcals.ExchangeCalendar:
    return xcals.get_calendar("XKRX", start=CALENDAR_START)


def sessions(start: str | pd.Timestamp, end: str | pd.Timestamp) -> pd.DatetimeIndex:
    """start~end(둘 다 포함) 영업일. 시간대 없는 자정 기준 날짜."""
    s = _xkrx().sessions_in_range(pd.Timestamp(start), pd.Timestamp(end))
    s = pd.DatetimeIndex(s)
    return (s.tz_localize(None) if s.tz is not None else s).normalize()


def is_session(day: pd.Timestamp) -> bool:
    return _xkrx().is_session(pd.Timestamp(day).normalize())
