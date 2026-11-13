package com.zjysky.controller;

import com.zjysky.common.database.entity.SysMenu;
import com.zjysky.service.MenuService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * web 前端菜单的数据接口（替代前端硬编码的 menuConfig）。
 *
 * <pre>
 * GET /api/menus                                分组树：{source,table,count,updatedAt,groups:[{key,title,children:[...]}]}
 * GET /api/menus/rows                           扁平原始行（含隐藏项），排查数据用
 * GET /api/menus/save?menuKey=..&amp;title=..&amp;...   新增（不带 id）/ 修改（带 id）一行菜单
 * GET /api/menus/remove?id=..                   删除一行（删分组时连带删除其子项）
 * </pre>
 *
 * <p>所有接口统一使用 GET：写操作也通过 query 参数传值；修改时未传的字段继承库中现值。
 * 不缓存，前端每次加载 / 点「刷新」都会重新读库；接口不可用时前端回退到本地静态菜单。</p>
 */
@RestController
@RequestMapping("/api/menus")
@CrossOrigin(origins = "*")
public class MenuApiController {

    private final MenuService menuService;

    public MenuApiController(MenuService menuService) {
        this.menuService = menuService;
    }

    @GetMapping
    public Map<String, Object> tree() {
        return menuService.tree();
    }

    @GetMapping("/rows")
    public List<SysMenu> rows() {
        return menuService.rows();
    }

    /**
     * 新增 / 修改一行菜单。id 为空表示新增；其余字段未传时，
     * 新增走 MenuService 的默认值，修改则继承库中现值。
     */
    @GetMapping("/save")
    public SysMenu save(
            @RequestParam(name = "id", required = false) Long id,
            @RequestParam(name = "parentId", required = false) Long parentId,
            @RequestParam(name = "menuKey", required = false) String menuKey,
            @RequestParam(name = "title", required = false) String title,
            @RequestParam(name = "path", required = false) String path,
            @RequestParam(name = "component", required = false) String component,
            @RequestParam(name = "sortNo", required = false) Integer sortNo,
            @RequestParam(name = "visible", required = false) Integer visible,
            @RequestParam(name = "closable", required = false) Integer closable) {
        SysMenu menu = new SysMenu();
        menu.setId(id);
        menu.setParentId(parentId);
        menu.setMenuKey(menuKey);
        menu.setTitle(title);
        menu.setPath(path);
        menu.setComponent(component);
        menu.setSortNo(sortNo);
        menu.setVisible(visible);
        menu.setClosable(closable);
        return menuService.save(menu);
    }

    @GetMapping("/remove")
    public Map<String, Object> remove(@RequestParam(name = "id") Long id) {
        return menuService.remove(id);
    }

    /** 参数 / 状态错误统一返回 {status, message}，前端直接显示 message。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException ex) {
        return error(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> unavailable(IllegalStateException ex) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
