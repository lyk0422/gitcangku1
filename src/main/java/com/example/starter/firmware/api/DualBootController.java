package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.DualBootTaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.service.DualBootTaskService;
import com.example.starter.firmware.service.ReleaseService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 双分区试启动发布：建单、试启动/健康确认/失败回执、任务查询。
 * 任务状态机 PENDING -> TRIAL -> COMMITTED/ROLLED_BACK，仅本题新建的双分区发布使用。
 */
@RestController
@RequestMapping("/api/dual-boot")
public class DualBootController {

    private final ReleaseService releaseService;
    private final DualBootTaskService dualBootTaskService;

    public DualBootController(ReleaseService releaseService, DualBootTaskService dualBootTaskService) {
        this.releaseService = releaseService;
        this.dualBootTaskService = dualBootTaskService;
    }

    @PostMapping("/releases")
    public ReleaseView create(@Valid @RequestBody CreateReleaseRequest request) {
        return releaseService.createDualBoot(request);
    }

    @PostMapping("/tasks/{taskId}/receipt")
    public DualBootTaskView receipt(@PathVariable long taskId,
                                    @Valid @RequestBody DualBootReceiptRequest request) {
        return dualBootTaskService.receipt(taskId, request);
    }

    @GetMapping("/tasks/{taskId}")
    public DualBootTaskView get(@PathVariable long taskId) {
        return dualBootTaskService.get(taskId);
    }

    @GetMapping("/releases/{releaseId}/tasks")
    public DualBootTaskListResponse tasks(@PathVariable long releaseId,
                                          @RequestParam(required = false) String status) {
        return dualBootTaskService.listByRelease(releaseId, parseStatus(status));
    }

    private DualBootTaskStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return DualBootTaskStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_STATUS", "未知任务状态: " + status);
        }
    }
}