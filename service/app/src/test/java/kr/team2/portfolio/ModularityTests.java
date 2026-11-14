package kr.team2.portfolio;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * 모듈 경계 검증 (docs/02 §3.2, 01 NFR-09).
 * 다른 모듈의 internal 패키지를 쓰거나, allowedDependencies에 없는 모듈을 쓰면 실패한다.
 */
class ModularityTests {

    private final ApplicationModules modules = ApplicationModules.of(PortfolioApplication.class);

    @Test
    void 모듈_경계를_지킨다() {
        modules.verify();
    }

    @Test
    void 모듈_문서를_만든다() {
        // build/spring-modulith-docs 에 모듈 구성도(PlantUML)와 설명이 생긴다
        new Documenter(modules)
                .writeModulesAsPlantUml()
                .writeIndividualModulesAsPlantUml()
                .writeModuleCanvases();
    }
}
