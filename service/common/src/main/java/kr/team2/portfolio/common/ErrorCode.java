package kr.team2.portfolio.common;

/**
 * 오류 코드 목록 (docs/02 §9). HTTP 상태와 사용자에게 보여줄 기본 문구를 함께 둔다.
 */
public enum ErrorCode {

    INVALID_PARAMETER(400, "요청 값을 확인해 주세요."),
    UNAUTHORIZED(401, "로그인이 필요해요."),
    FORBIDDEN(403, "허용되지 않은 요청이에요."),

    PROFILE_NOT_FOUND(404, "투자 성향 진단 결과가 없어요."),
    RECOMMENDATION_NOT_FOUND(404, "추천 결과를 찾을 수 없어요."),
    PORTFOLIO_NOT_FOUND(404, "포트폴리오가 없어요."),
    ALERT_NOT_FOUND(404, "알림을 찾을 수 없어요."),
    BACKTEST_NOT_FOUND(404, "백테스트를 찾을 수 없어요."),
    STOCK_NOT_FOUND(404, "종목을 찾을 수 없어요."),
    NOT_FOUND(404, "찾을 수 없어요."),

    USERNAME_TAKEN(409, "이미 쓰고 있는 아이디예요."),
    ALREADY_ONBOARDED(409, "이미 온보딩을 마쳤어요."),
    STALE_RECOMMENDATION(409, "최신 추천 결과로만 매수 완료할 수 있어요."),
    ALREADY_PURCHASED(409, "이미 매수 완료한 추천이에요."),
    INSUFFICIENT_UNIVERSE(409, "조건에 맞는 종목이 너무 적어요."),
    INSUFFICIENT_HISTORY(409, "계산에 필요한 과거 데이터가 부족해요."),
    NOTHING_TO_REBALANCE(409, "평가금액이 없어 리밸런싱할 것이 없어요."),
    ALERT_CLOSED(409, "이미 닫힌 알림이에요."),

    VALIDATION_ERROR(422, "입력 값을 확인해 주세요."),
    UNKNOWN_STOCK(422, "없는 종목코드가 있어요."),
    INVALID_SURVEY(422, "설문 응답을 확인해 주세요."),

    INTERNAL_ERROR(500, "잠시 후 다시 시도해 주세요."),
    DATA_NOT_LOADED(503, "시장 데이터가 아직 준비되지 않았어요."),
    QUANT_UNAVAILABLE(503, "계산 서버에 연결할 수 없어요. 잠시 후 다시 시도해 주세요.");

    private final int status;
    private final String defaultMessage;

    ErrorCode(int status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public int status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
