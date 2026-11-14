package kr.team2.portfolio.alert.api;

/** 리밸런싱 알림 종류 (docs/03 §1.10). */
public enum AlertType {
    /** 종목 쏠림 */ R1,
    /** 업종 쏠림 */ R2,
    /** 목표 비중 이탈 */ R3,
    /** 안전자산 감소 */ R4,
    /** 손실 한도 근접 */ R5,
    /** 정기 점검 */ R6,
    /** 상장폐지 위험 */ R7
}
