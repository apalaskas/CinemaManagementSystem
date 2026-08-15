-- Run as a MySQL 8.4 administrator after replacing the example password.
-- The single academic-deployment account has runtime DML plus the DDL required by Flyway.
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;
SET default_storage_engine = InnoDB;

CREATE DATABASE IF NOT EXISTS cinema_management
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

CREATE USER IF NOT EXISTS 'cinema_app'@'localhost'
    IDENTIFIED BY 'REPLACE_WITH_A_STRONG_LOCAL_PASSWORD';

GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES
    ON cinema_management.* TO 'cinema_app'@'localhost';

FLUSH PRIVILEGES;
