package com.zjysky.controller;

import com.zjysky.service.CrawlQueryService;
import com.zjysky.service.CrawlTaskService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「价格爬取」页的后端接口（前后端分离，前端 web 通过 Vite 的 /api 代理访问）。
 *
 * <pre>
 * GET  /api/crawl/status                        四源交易日 / 收盘状态
 * GET  /api/crawl/run?source=..&amp;mode=..&amp;date=..  触发一次采集（异步，立即返回任务）
 * GET  /api/crawl/tasks                        最近任务及状态
 * GET  /api/crawl/logs?source=&amp;limit=            md_crawl_log 采集日志
 * GET  /api/crawl/prices?source=&amp;date=&amp;limit=     md_price_* 当日价格数据
 * </pre>
 */
@RestController
@RequestMapping("/api/crawl")
@CrossOrigin(origins = "*")
public class CrawlApiController {

    private final CrawlTaskService taskService;
    private final CrawlQueryService queryService;

    public CrawlApiController(CrawlTaskService taskService, CrawlQueryService queryService) {
        this.taskService = taskService;
        this.queryService = queryService;
    }

    @GetMapping("/status")
    public List<Map<String, Object>> status() {
        return queryService.status();
    }

    /**
     * 触发采集。mode：
     * scheduled = 走 crawler 的完整守卫决策（交易日 / 盘中实时 / 收盘定稿 / 幂等）；
     * realtime  = 只采实时（仅 SHFE、SGE 支持）；
     * history   = 采指定交易日的收盘数据，date 必填（yyyy-MM-dd）。
     */
    @GetMapping("/run")
    public CrawlTaskService.TaskInfo run(
            @RequestParam String source,
            @RequestParam(defaultValue = "scheduled") String mode,
            @RequestParam(required = false) String date) {
        try {
            return taskService.submit(source, mode, date);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @GetMapping("/tasks")
    public Map<String, Object> tasks(@RequestParam(defaultValue = "10") int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("running", taskService.runningSources());
        out.put("items", taskService.recent(limit));
        return out;
    }

    @GetMapping("/logs")
    public Object logs(@RequestParam(required = false) String source,
                       @RequestParam(defaultValue = "20") int limit) {
        try {
            return queryService.logs(source, limit);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/prices")
    public Object prices(@RequestParam String source,
                         @RequestParam(required = false) String date,
                         @RequestParam(defaultValue = "100") int limit) {
        try {
            return queryService.prices(source, date, limit);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** 把参数错误 / 重复提交的原因以 {status, message} 返回，前端直接显示 message。 */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleError(ResponseStatusException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", ex.getStatusCode().value());
        body.put("message", ex.getReason());
        return ResponseEntity.status(ex.getStatusCode()).body(body);
    }
}
