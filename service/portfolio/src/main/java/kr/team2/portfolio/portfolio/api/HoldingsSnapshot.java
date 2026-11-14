package kr.team2.portfolio.portfolio.api;

import java.util.List;

/** 추천 계산에 넘길 현재 보유 (docs/03 §3 I6 {@code existing}). */
public record HoldingsSnapshot(List<Holding> holdings, double bondUnits, long cash) {

    public record Holding(String code, int quantity) {
    }

    public static HoldingsSnapshot empty() {
        return new HoldingsSnapshot(List.of(), 0, 0);
    }
}
