package kr.team2.portfolio;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 실제 MySQL 8.4 컨테이너를 쓰는 통합 테스트의 부모. Docker가 없으면 하위 테스트 전부를 건너뛴다.
 * <p>
 * 컨테이너는 JVM에 하나만 띄우고 끝까지 쓴다(종료 시 Testcontainers가 정리).
 * {@code @Container}로 두면 테스트 클래스마다 컨테이너를 껐다 켜는데, 설정이 같은 테스트 클래스끼리는
 * Spring 컨텍스트를 재사용하므로 꺼진 컨테이너 주소를 붙잡게 된다.
 */
@Testcontainers(disabledWithoutDocker = true)
public abstract class MySqlIntegrationTest {

    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    static {
        MYSQL.start();
    }
}
