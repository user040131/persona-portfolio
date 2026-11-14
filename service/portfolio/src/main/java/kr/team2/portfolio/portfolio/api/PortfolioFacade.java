package kr.team2.portfolio.portfolio.api;

import java.util.Optional;
import kr.team2.portfolio.quantclient.api.EvaluateRequest;
import tools.jackson.databind.node.ObjectNode;

/** 다른 모듈과 web이 포트폴리오를 쓰는 창구. */
public interface PortfolioFacade {

    /** 현재 보유. 포트폴리오가 없으면 빈 스냅샷. */
    HoldingsSnapshot holdingsSnapshot(long userId);

    /** 매수 완료를 한 번이라도 했는지. */
    boolean hasPurchased(long userId);

    /**
     * 매수 완료 반영 (docs/03 §6.4 2~5단계). 수량 합산·평균가 갱신, 채권·현금 더하기, 목표 비중 교체.
     * 호출한 쪽 트랜잭션에 참여한다.
     *
     * @throws kr.team2.portfolio.common.BusinessException PORTFOLIO_NOT_FOUND
     */
    void purchase(long userId, PurchaseOrder order);

    /**
     * 최신 종가로 평가 (엔진 I8, 01 FR-PF-02·03). 종목명·평균가·수익률·목표 비중을 붙인 화면용 JSON이다.
     * {@code conditions}에는 엔진이 판정한 알림 조건(R1~R7)이 그대로 들어 있다.
     *
     * @throws kr.team2.portfolio.common.BusinessException PORTFOLIO_NOT_FOUND, QUANT_UNAVAILABLE
     */
    ObjectNode evaluate(long userId);

    /**
     * 수량·평균가·채권·현금 직접 수정 (01 FR-PF-04). 기준일을 바꾸고 {@link HoldingsChanged}를 낸다.
     *
     * @throws kr.team2.portfolio.common.BusinessException VALIDATION_ERROR, UNKNOWN_STOCK
     */
    void update(long userId, PortfolioUpdate update);

    /** 알림 상태를 붙일 포트폴리오 id. */
    Optional<Long> portfolioId(long userId);

    /** 지금 상태로 만든 엔진 요청 (I8, I9에 같이 쓴다). */
    EvaluateRequest engineRequest(long userId);

    /** R6 정기 점검 알림을 연 해. 엔진은 이 해에는 R6를 다시 내지 않는다 (docs/03 §6.5). */
    void markR6Opened(long userId, int year);

    /**
     * 리밸런싱 제안대로 했다고 기록 (01 FR-ALT-09). 수량·채권·현금을 거래 후 값으로 바꾸고 목표 비중을 교체한다.
     * 더 산 종목은 평균가를 다시 계산하고, 판 종목은 평균가를 그대로 둔다. {@link HoldingsChanged}를 낸다.
     */
    void applyRebalance(long userId, RebalanceOrder order);
}
