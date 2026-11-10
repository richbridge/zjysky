-- ============================================================
-- SHFE 爬虫落库前置脚本：用有权限的账号（如 root）执行一次即可
--
-- 当前连接配置（crawler/src/main/resources/application.yml → spring.datasource.*）：
--   库名 zjysky / 用户名 zjysky / 密码 123456
-- 本机该账号已授权，可直接使用；本脚本用于新环境初始化或权限重建。
--
-- 落库目标表 md_price_shfe 是库中已存在的统一价格宽表：本模块不建表、不删表、不改表，
-- 只在启动时通过 information_schema 做字段完整性自检；本脚本也不会改动它。
-- 库中已有的 md_price_* / sys_* 等表属于其他业务，本脚本不会改动它们。
-- ============================================================

CREATE DATABASE IF NOT EXISTS zjysky
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_general_ci;

-- 若 zjysky 用户已存在，可跳过此行
CREATE USER IF NOT EXISTS 'zjysky'@'localhost' IDENTIFIED BY '123456';

GRANT ALL PRIVILEGES ON zjysky.* TO 'zjysky'@'localhost';
FLUSH PRIVILEGES;
