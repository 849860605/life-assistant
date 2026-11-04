package com.lifeassistant.api.web.admin;

import com.lifeassistant.api.auth.AdminContext;
import com.lifeassistant.common.api.ApiResponse;
import com.lifeassistant.core.audit.AdminStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 管理端总览和操作日志。 */
@RestController
@RequestMapping("/admin/api/v1")
@RequiredArgsConstructor
public class AdminDashboardController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AdminStatsService statsService;
    private final AdminContext adminContext;

    /** GET /admin/api/v1/overview：用户数、对话数、模型调用和费用、工具成功率、近 7 天趋势。 */
    @GetMapping("/overview")
    public ApiResponse<AdminStatsService.Overview> overview() {
        adminContext.current();
        return ApiResponse.ok(statsService.overview());
    }

    /** GET /admin/api/v1/op-logs?action=&adminId=&page=1&size=20：操作日志，按时间倒序。 */
    @GetMapping("/op-logs")
    public ApiResponse<AdminStatsService.Page<AdminStatsService.OpLogRow>> opLogs(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Long adminId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        adminContext.current();
        return ApiResponse.ok(statsService.opLogs(action, adminId, Math.max(page, 1),
                Math.min(Math.max(size, 1), MAX_PAGE_SIZE)));
    }

    /** GET /admin/api/v1/op-logs/actions：出现过的操作类型，筛选用。 */
    @GetMapping("/op-logs/actions")
    public ApiResponse<List<String>> opActions() {
        adminContext.current();
        return ApiResponse.ok(statsService.opActions());
    }
}
