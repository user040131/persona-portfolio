-- app 스키마 초기 테이블 (docs/03 §4.2). 테이블마다 소유 모듈을 적어 둔다.
-- 다른 모듈의 테이블을 리포지토리로 직접 읽지 않는다 (docs/02 §3.2).

-- [auth]
CREATE TABLE users (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(20)  NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    created_at    DATETIME(3)  NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- [profile]
CREATE TABLE profile (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT      NOT NULL,
    version     INT         NOT NULL,
    answers     JSON        NOT NULL,
    total_score INT         NOT NULL,
    grade       VARCHAR(12) NOT NULL,
    settings    JSON        NOT NULL,
    is_current  TINYINT(1)  NOT NULL DEFAULT 1,
    created_at  DATETIME(3) NOT NULL,
    UNIQUE KEY ux_profile_ver (user_id, version),
    CONSTRAINT fk_profile_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- [profile]
CREATE TABLE onboarding_state (
    user_id               BIGINT PRIMARY KEY,
    pending_invest_amount BIGINT,
    completed_at          DATETIME(3) NOT NULL,
    CONSTRAINT fk_onb_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- [portfolio]
CREATE TABLE portfolio (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id             BIGINT      NOT NULL UNIQUE,
    bond_units          DOUBLE      NOT NULL DEFAULT 0,
    cash                BIGINT      NOT NULL DEFAULT 0,
    purchased_at        DATE,
    holdings_changed_at DATE        NOT NULL,
    last_r6_year        SMALLINT,
    created_at          DATETIME(3) NOT NULL,
    version             BIGINT      NOT NULL DEFAULT 0,  -- JPA 낙관적 락
    CONSTRAINT fk_pf_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- [portfolio] code는 market 스키마 종목이라 FK 없음. 저장 전에 StockCatalog로 검증
CREATE TABLE holding (
    portfolio_id BIGINT      NOT NULL,
    code         VARCHAR(10) NOT NULL,
    quantity     INT         NOT NULL,
    avg_price    DOUBLE,
    origin       VARCHAR(10) NOT NULL,  -- EXISTING | SERVICE
    PRIMARY KEY (portfolio_id, code),
    CONSTRAINT fk_holding_pf FOREIGN KEY (portfolio_id) REFERENCES portfolio (id) ON DELETE CASCADE,
    CONSTRAINT ck_holding_qty CHECK (quantity > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- [portfolio] asset = 종목코드 | 'BOND' | 'CASH'
CREATE TABLE target_weight (
    portfolio_id BIGINT      NOT NULL,
    asset        VARCHAR(10) NOT NULL,
    weight       DOUBLE      NOT NULL,
    set_at       DATETIME(3) NOT NULL,
    PRIMARY KEY (portfolio_id, asset),
    CONSTRAINT fk_tw_pf FOREIGN KEY (portfolio_id) REFERENCES portfolio (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- [alert] 같은 (포트폴리오, 종류, 대상)의 열린 알림은 하나만 (active_key 유일)
-- active_key는 VIRTUAL이어야 한다: MySQL은 STORED 계산 열의 원본 열(portfolio_id)에
-- ON DELETE CASCADE 외래키를 허용하지 않는다.
CREATE TABLE alert (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    portfolio_id BIGINT      NOT NULL,
    type         CHAR(2)     NOT NULL,
    target       VARCHAR(50) NOT NULL DEFAULT '',
    status       VARCHAR(10) NOT NULL,  -- OPEN | CLOSED | DISMISSED
    detail       JSON        NOT NULL,
    opened_at    DATETIME(3) NOT NULL,
    closed_at    DATETIME(3),
    active_key   VARCHAR(80) GENERATED ALWAYS AS (
        CASE WHEN status IN ('OPEN', 'DISMISSED') THEN CONCAT(portfolio_id, ':', type, ':', target) END
    ) VIRTUAL,
    UNIQUE KEY ux_alert_active (active_key),
    CONSTRAINT fk_alert_pf FOREIGN KEY (portfolio_id) REFERENCES portfolio (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- [recommendation]
CREATE TABLE recommendation (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id           BIGINT      NOT NULL,
    profile_id        BIGINT      NOT NULL,
    data_version      BIGINT      NOT NULL,
    as_of             DATE        NOT NULL,
    invest_amount     BIGINT      NOT NULL,
    latest_version    INT         NOT NULL,
    purchased_version INT,
    created_at        DATETIME(3) NOT NULL,
    KEY ix_rec_user (user_id, created_at),
    CONSTRAINT fk_rec_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- [recommendation] result = 엔진 I6 응답 본문 그대로 (facts·screening 포함)
CREATE TABLE recommendation_version (
    recommendation_id BIGINT      NOT NULL,
    version           INT         NOT NULL,
    edits             JSON        NOT NULL,
    result            JSON        NOT NULL,
    created_at        DATETIME(3) NOT NULL,
    PRIMARY KEY (recommendation_id, version),
    CONSTRAINT fk_rv_rec FOREIGN KEY (recommendation_id) REFERENCES recommendation (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- [backtest]
CREATE TABLE backtest_job (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT      NOT NULL,
    job_id      CHAR(36)    NOT NULL UNIQUE,
    params      JSON        NOT NULL,
    last_status VARCHAR(10) NOT NULL,
    created_at  DATETIME(3) NOT NULL,
    CONSTRAINT fk_bj_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
