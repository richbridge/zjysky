package com.zjysky.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zjysky.common.database.entity.SysMenu;
import com.zjysky.common.database.mapper.SysMenuMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 前端菜单服务：sys_menu → 前端要的分组树。
 *
 * <p>不缓存：每次 GET /api/menus 都读库，数据库里改名 / 排序 / 隐藏后前端点「刷新」即可生效。
 * 实体与 Mapper 来自 common，业务组装留在 admin（持久层收敛、service 各模块自持）。</p>
 */
@Service
public class MenuService {

    private static final String INIT_HINT = "请先执行 admin/src/main/resources/db-menu-init.sql 初始化菜单表";

    private final SysMenuMapper menuMapper;

    public MenuService(SysMenuMapper menuMapper) {
        this.menuMapper = menuMapper;
    }

    /** 全部行（含隐藏项），供 /api/menus/rows 排查数据用。 */
    public List<SysMenu> rows() {
        try {
            return menuMapper.selectList(new QueryWrapper<SysMenu>()
                    .orderByAsc("parent_id", "sort_no", "id"));
        } catch (DataAccessException e) {
            throw new IllegalStateException("菜单表 sys_menu 读取失败：" + e.getMostSpecificCause().getMessage()
                    + "；" + INIT_HINT);
        }
    }

    /** 前端消费的树：{source, table, count, updatedAt, groups:[{key,title,sortNo,children:[...]}]}。 */
    public Map<String, Object> tree() {
        List<SysMenu> all = rows();

        Map<Long, Map<String, Object>> groupById = new LinkedHashMap<>();
        for (SysMenu row : all) {
            if (!isGroup(row) || !visible(row)) continue;
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("key", row.getMenuKey());
            group.put("title", row.getTitle());
            group.put("sortNo", row.getSortNo());
            group.put("children", new ArrayList<Map<String, Object>>());
            groupById.put(row.getId(), group);
        }
        // 分组按 sortNo 再排一次（数据库排序以 parent_id 优先，这里显式保证）
        List<Map<String, Object>> groups = new ArrayList<>(groupById.values());
        groups.sort(Comparator.comparingInt(g -> intValue(g.get("sortNo"))));

        for (SysMenu row : all) {
            if (isGroup(row) || !visible(row)) continue;
            Map<String, Object> group = groupById.get(row.getParentId());
            if (group == null || row.getPath() == null || row.getPath().isBlank()) continue;
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> children = (List<Map<String, Object>>) group.get("children");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId());
            item.put("path", row.getPath());
            item.put("name", row.getTitle());
            item.put("component", row.getComponent());
            item.put("closable", row.getClosable() == null || row.getClosable() != 0);
            item.put("sortNo", row.getSortNo());
            children.add(item);
        }
        for (Map<String, Object> group : groups) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> children = (List<Map<String, Object>>) group.get("children");
            children.sort(Comparator.comparingInt(c -> intValue(c.get("sortNo"))));
        }

        LocalDateTime latest = all.stream()
                .map(SysMenu::getUpdateTime)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", "database");
        out.put("table", "sys_menu");
        out.put("count", all.size());
        out.put("updatedAt", latest == null ? null : latest.toString());
        out.put("groups", groups);
        return out;
    }

    /** 新增或更新一条菜单；前端不调用，供维护菜单数据时使用。 */
    public SysMenu save(SysMenu menu) {
        if (menu == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        menu.setMenuKey(trim(menu.getMenuKey()));
        menu.setTitle(trim(menu.getTitle()));
        menu.setPath(trim(menu.getPath()));
        menu.setComponent(trim(menu.getComponent()));

        // 更新时允许只传要改的字段：未传的字段继承库中现值
        if (menu.getId() != null) {
            SysMenu existing = menuMapper.selectById(menu.getId());
            if (existing == null) {
                throw new IllegalArgumentException("菜单不存在：id=" + menu.getId());
            }
            if (menu.getMenuKey() == null) menu.setMenuKey(existing.getMenuKey());
            if (menu.getTitle() == null) menu.setTitle(existing.getTitle());
            if (menu.getPath() == null) menu.setPath(existing.getPath());
            if (menu.getComponent() == null) menu.setComponent(existing.getComponent());
            if (menu.getParentId() == null) menu.setParentId(existing.getParentId());
            if (menu.getSortNo() == null) menu.setSortNo(existing.getSortNo());
            if (menu.getVisible() == null) menu.setVisible(existing.getVisible());
            if (menu.getClosable() == null) menu.setClosable(existing.getClosable());
        }

        if (menu.getMenuKey() == null || menu.getMenuKey().isEmpty()) {
            throw new IllegalArgumentException("menuKey 不能为空");
        }
        if (menu.getTitle() == null || menu.getTitle().isEmpty()) {
            throw new IllegalArgumentException("title 不能为空");
        }
        if (menu.getSortNo() == null) menu.setSortNo(0);
        if (menu.getVisible() == null) menu.setVisible(1);
        if (menu.getClosable() == null) menu.setClosable(1);
        if (menu.getParentId() == null) menu.setParentId(0L);

        if (duplicate("menu_key", menu.getMenuKey(), menu.getId())) {
            throw new IllegalArgumentException("menuKey 已存在：" + menu.getMenuKey());
        }

        if (menu.getParentId() == 0L) {
            if (menu.getPath() != null && !menu.getPath().isEmpty()) {
                throw new IllegalArgumentException("分组不能配置 path");
            }
            menu.setPath(null);
            menu.setComponent(null);
        } else {
            SysMenu parent = menuMapper.selectById(menu.getParentId());
            if (parent == null || !isGroup(parent)) {
                throw new IllegalArgumentException("parentId 不是有效的菜单分组：" + menu.getParentId());
            }
            if (menu.getPath() == null || !menu.getPath().startsWith("/")) {
                throw new IllegalArgumentException("菜单项 path 必须以 / 开头");
            }
            if (duplicate("path", menu.getPath(), menu.getId())) {
                throw new IllegalArgumentException("path 已存在：" + menu.getPath());
            }
        }

        try {
            if (menu.getId() == null) {
                menuMapper.insert(menu);
            } else {
                menuMapper.updateById(menu);
            }
        } catch (DataAccessException e) {
            throw new IllegalStateException("菜单写入失败：" + e.getMostSpecificCause().getMessage()
                    + "；" + INIT_HINT);
        }
        return menu;
    }

    /** 删除菜单；删分组时连同其下子项一起删除。 */
    public Map<String, Object> remove(Long id) {
        SysMenu row = menuMapper.selectById(id);
        if (row == null) {
            throw new IllegalArgumentException("菜单不存在：id=" + id);
        }
        int children = 0;
        if (isGroup(row)) {
            children = menuMapper.delete(new QueryWrapper<SysMenu>().eq("parent_id", id));
        }
        menuMapper.deleteById(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", 200);
        out.put("message", "已删除菜单 " + row.getMenuKey() + (children > 0 ? " 及其 " + children + " 个子项" : ""));
        return out;
    }

    private boolean duplicate(String column, String value, Long excludeId) {
        QueryWrapper<SysMenu> query = new QueryWrapper<SysMenu>().eq(column, value);
        if (excludeId != null) query.ne("id", excludeId);
        return menuMapper.selectCount(query) > 0;
    }

    private static boolean isGroup(SysMenu row) {
        return row.getParentId() == null || row.getParentId() == 0L;
    }

    private static boolean visible(SysMenu row) {
        return row.getVisible() == null || row.getVisible() != 0;
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
