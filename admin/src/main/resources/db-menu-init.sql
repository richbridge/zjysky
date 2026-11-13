-- =============================================================
-- web 前端菜单数据源（前后端分离联动）
-- 数据库：zjysky    执行方式：mysql -u<user> -p zjysky < db-menu-init.sql
--
-- 本脚本可重复执行：CREATE TABLE IF NOT EXISTS + INSERT IGNORE，
-- 已经改过的菜单名称 / 排序 / 可见性不会被覆盖。
--
-- 表里一行 = 一个菜单分组（parent_id = 0）或一个菜单项（parent_id = 分组 id）。
-- 分组只有 key/title；菜单项的 path 同时是前端路由地址、Menu.Item key、页签 key。
-- component 对应 web/src/config/menuConfig.jsx 的组件注册表；找不到时前端渲染占位页。
-- =============================================================

CREATE TABLE IF NOT EXISTS sys_menu (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    parent_id   BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '父分组 id，0 表示分组本身',
    menu_key    VARCHAR(64)  NOT NULL COMMENT '稳定标识：分组 key 或菜单项 key',
    title       VARCHAR(64)  NOT NULL COMMENT '显示名称（分组标题 / 菜单项名称）',
    path        VARCHAR(128)          DEFAULT NULL COMMENT '前端路由 path，分组为 NULL',
    component   VARCHAR(64)           DEFAULT NULL COMMENT '前端组件注册名，空则按 path 回退或占位',
    sort_no     INT          NOT NULL DEFAULT 0 COMMENT '同级排序，升序',
    visible     TINYINT      NOT NULL DEFAULT 1 COMMENT '1 显示 / 0 隐藏',
    closable    TINYINT      NOT NULL DEFAULT 1 COMMENT '页签是否可关闭',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_menu_key  (menu_key),
    UNIQUE KEY uk_sys_menu_path (path),
    KEY idx_sys_menu_parent (parent_id, sort_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT = 'web 前端菜单（左侧菜单 / 路由 / 页签的数据源）';

-- 分组（子项通过 parent_id 关联）
INSERT IGNORE INTO sys_menu (id, parent_id, menu_key, title, path, component, sort_no, visible, closable) VALUES
    (1, 0, 'sub1', '销售管理', NULL, NULL, 1, 1, 1);

-- 菜单项：与 web/src/config/menuConfig.jsx 的组件注册表对应
INSERT IGNORE INTO sys_menu (id, parent_id, menu_key, title, path, component, sort_no, visible, closable) VALUES
    (11, 1, 'sale',             '销售管理', '/sale',             'SalePage',       1, 1, 1),
    (12, 1, 'sale-price-crawl', '价格爬取', '/sale/price-crawl', 'PriceCrawlPage', 2, 1, 1),
    (13, 1, 'sale-config',      '销售设置', '/sale-config',      'Placeholder',    3, 1, 1);
