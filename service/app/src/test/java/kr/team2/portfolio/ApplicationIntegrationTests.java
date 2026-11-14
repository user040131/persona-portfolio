package kr.team2.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 앱 전체를 실제 MySQL로 띄워 Flyway 마이그레이션을 확인한다. */
@SpringBootTest
class ApplicationIntegrationTests extends MySqlIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void Flyway가_app_스키마_테이블을_만든다() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()",
                String.class);
        assertThat(tables).contains(
                "users", "profile", "onboarding_state", "portfolio", "holding", "target_weight",
                "alert", "recommendation", "recommendation_version", "backtest_job");
    }
}
