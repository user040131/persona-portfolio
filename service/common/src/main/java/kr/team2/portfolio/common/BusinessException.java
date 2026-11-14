package kr.team2.portfolio.common;

import java.util.Map;

/**
 * 업무 규칙 위반을 나타내는 예외. web 모듈의 예외 처리기가 {@link ErrorResponse}로 바꾼다.
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> detail;

    public BusinessException(ErrorCode code) {
        this(code, code.defaultMessage(), Map.of());
    }

    public BusinessException(ErrorCode code, Map<String, Object> detail) {
        this(code, code.defaultMessage(), detail);
    }

    public BusinessException(ErrorCode code, String message, Map<String, Object> detail) {
        super(message);
        this.code = code;
        this.detail = detail == null ? Map.of() : Map.copyOf(detail);
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> detail() {
        return detail;
    }
}
