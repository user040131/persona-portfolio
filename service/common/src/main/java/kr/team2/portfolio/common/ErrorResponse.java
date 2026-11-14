package kr.team2.portfolio.common;

import java.util.Map;

/**
 * 공통 오류 응답 형식 (docs/03 §2.2).
 * <pre>{"error": {"code": "...", "message": "...", "detail": {...}}}</pre>
 */
public record ErrorResponse(Body error) {

    public record Body(String code, String message, Map<String, Object> detail) {
    }

    public static ErrorResponse of(ErrorCode code) {
        return new ErrorResponse(new Body(code.name(), code.defaultMessage(), Map.of()));
    }

    public static ErrorResponse of(ErrorCode code, String message, Map<String, Object> detail) {
        return new ErrorResponse(new Body(code.name(), message, detail == null ? Map.of() : detail));
    }

    public static ErrorResponse of(BusinessException e) {
        return of(e.code(), e.getMessage(), e.detail());
    }
}
