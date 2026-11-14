package kr.team2.portfolio.alert.api;

import java.time.LocalDateTime;

/**
 * 화면에 보일 알림 하나 (docs/03 §6.3 알림 문구).
 *
 * @param target 종목코드(R1·R3·R7)·업종명(R2)·자산(R3의 BOND·CASH) 또는 빈 문자열
 * @param title  짧은 제목 (예: "종목 쏠림")
 * @param status OPEN · DISMISSED · CLOSED
 */
public record AlertView(long alertId, AlertType type, String target, String title, String text, String status,
                        LocalDateTime openedAt) {
}
