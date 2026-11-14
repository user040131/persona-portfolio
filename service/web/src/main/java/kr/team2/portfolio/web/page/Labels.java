package kr.team2.portfolio.web.page;

import java.util.Map;
import kr.team2.portfolio.profile.api.Factor;
import kr.team2.portfolio.profile.api.Method;

/** 화면에 보이는 한국어 라벨. 계산 서비스(Python)에는 라벨을 두지 않는다 (결정 11-2). */
final class Labels {

    static final Map<Factor, String> FACTOR = Map.of(
            Factor.VALUE, "가치",
            Factor.QUALITY, "수익성",
            Factor.MOMENTUM, "추세",
            Factor.STABILITY, "안정성",
            Factor.DIVIDEND, "배당");

    static final Map<Method, String> METHOD = Map.of(
            Method.EQUAL, "동일가중",
            Method.INVERSE_VOL, "변동성 역수가중",
            Method.MIN_VARIANCE, "최소분산");

    private Labels() {
    }
}
