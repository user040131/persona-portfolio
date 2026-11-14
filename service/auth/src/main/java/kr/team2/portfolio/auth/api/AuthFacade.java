package kr.team2.portfolio.auth.api;

/** 회원 관련 창구 (01 FR-AUTH). */
public interface AuthFacade {

    /**
     * 회원가입. 아이디는 소문자로 바꿔 저장한다.
     *
     * @throws kr.team2.portfolio.common.BusinessException USERNAME_TAKEN, VALIDATION_ERROR
     */
    CurrentUser register(String username, String rawPassword);
}
