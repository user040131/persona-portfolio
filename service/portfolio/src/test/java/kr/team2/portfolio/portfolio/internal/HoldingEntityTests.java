package kr.team2.portfolio.portfolio.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 매수 완료 시 수량·평균가 (docs/03 §6.4, T-SP-BUY-01). */
class HoldingEntityTests {

    @Test
    void T_SP_BUY_01_기존_20주_230000원에_5주_247500원을_더하면_25주_233500원() {
        HoldingEntity h = new HoldingEntity(1L, "005930", 20, 230_000.0, HoldingEntity.Origin.SERVICE);
        h.buy(5, 247_500);
        assertThat(h.getQuantity()).isEqualTo(25);
        assertThat(h.getAvgPrice()).isEqualTo(233_500.0);
        assertThat(h.getOrigin()).isEqualTo(HoldingEntity.Origin.SERVICE);
    }

    @Test
    void 온보딩_때_받은_보유는_평균가를_모르므로_그대로_모른다() {
        HoldingEntity h = new HoldingEntity(1L, "005930", 20, null, HoldingEntity.Origin.EXISTING);
        h.buy(5, 247_500);
        assertThat(h.getQuantity()).isEqualTo(25);
        assertThat(h.getAvgPrice()).isNull();
        assertThat(h.getOrigin()).isEqualTo(HoldingEntity.Origin.EXISTING);
    }
}
