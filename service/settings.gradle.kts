plugins {
    // 다른 PC에 JDK 21이 없으면 자동으로 받아온다
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "portfolio-service"

include(
    "common",
    "quant-client",
    "auth",
    "profile",
    "portfolio",
    "recommendation",
    "alert",
    "backtest",
    "web",
    "app",
)
