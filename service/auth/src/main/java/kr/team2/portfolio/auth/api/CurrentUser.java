package kr.team2.portfolio.auth.api;

/** 로그인한 사용자. web 모듈이 컨트롤러에서 꺼내 쓴다. */
public record CurrentUser(long id, String username) {
}
