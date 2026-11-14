package kr.team2.portfolio.web.api;

import java.util.LinkedHashMap;
import java.util.Map;
import kr.team2.portfolio.common.BusinessException;
import kr.team2.portfolio.common.ErrorCode;
import kr.team2.portfolio.common.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * {@code /api/**} 오류를 공통 형식(docs/03 §2.2)으로 바꾼다. 페이지 오류는 error/*.html이 처리한다.
 */
@RestControllerAdvice(annotations = RestController.class)
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> business(BusinessException e) {
        return ResponseEntity.status(e.code().status()).body(ErrorResponse.of(e));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> invalid(MethodArgumentNotValidException e) {
        Map<String, Object> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(f -> fields.put(f.getField(), f.getDefaultMessage()));
        ErrorCode code = ErrorCode.VALIDATION_ERROR;
        return ResponseEntity.status(code.status())
                .body(ErrorResponse.of(code, code.defaultMessage(), Map.of("fields", fields)));
    }

    /** JSON 형식이 깨졌거나 타입이 맞지 않음 (예: 숫자 자리에 문자). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> unreadable(HttpMessageNotReadableException e) {
        ErrorCode code = ErrorCode.VALIDATION_ERROR;
        return ResponseEntity.status(code.status())
                .body(ErrorResponse.of(code, "요청 형식을 읽을 수 없어요.", Map.of()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception e) {
        log.error("처리하지 못한 오류", e);
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        return ResponseEntity.status(code.status()).body(ErrorResponse.of(code));
    }
}
