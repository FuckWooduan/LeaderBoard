package com.rankharvester.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 网站访问统计（前台首屏调用一次，按 IP 去重）。 */
@RestController
@RequestMapping("/api/public")
public class VisitController {

    private final VisitStatsService stats;

    public VisitController(VisitStatsService stats) {
        this.stats = stats;
    }

    @GetMapping("/visit")
    public Map<String, Object> visit(HttpServletRequest req) {
        var s = stats.recordAndGet(ClientIp.of(req));
        long internalCalls = stats.internalApiCalls();
        long totalCalls = Math.max(stats.totalApiCalls(), internalCalls); // total 至少不小于 internal
        long todayCalls = Math.min(stats.todayApiCalls(), totalCalls); // 今日不超过累计
        return Map.of("today", s.today(), "total", s.total(),
                "apiCalls", internalCalls, "apiCallsInternal", internalCalls, "apiCallsTotal", totalCalls,
                "apiCallsToday", todayCalls);
    }

}
