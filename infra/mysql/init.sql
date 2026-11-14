-- 스키마 2개와 서비스별 계정 (docs/02 §6). MySQL 컨테이너가 처음 뜰 때 한 번 실행된다.
-- 비밀번호는 docker-compose가 넘기는 값으로 바꾸지 못하므로, 로컬 개발용 기본값을 쓰고
-- 운영 환경에서는 직접 ALTER USER로 바꾼다.

CREATE DATABASE IF NOT EXISTS app    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS market CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE USER IF NOT EXISTS 'app_user'@'%'   IDENTIFIED BY 'app_pw';
CREATE USER IF NOT EXISTS 'quant_user'@'%' IDENTIFIED BY 'quant_pw';

-- 서로 상대 스키마에는 권한이 없다
GRANT ALL PRIVILEGES ON app.*    TO 'app_user'@'%';
GRANT ALL PRIVILEGES ON market.* TO 'quant_user'@'%';
FLUSH PRIVILEGES;
