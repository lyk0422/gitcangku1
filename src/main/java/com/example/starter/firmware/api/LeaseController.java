package com.example.starter.firmware.api;

import com.example.starter.firmware.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任务执行租约：领取/接管、续租与租约模式回执。
 */
@RestController
@RequestMapping("/api/tasks")
public class LeaseController {

    private final TaskService taskService;

    public LeaseController(TaskService taskService) {
        this.taskService = taskService;
    }

    /** 领取（或到期接管）租约，得到从 1 递增的 token 与 30 秒后到期时刻。 */
    @PostMapping("/{taskId}/leases")
    public LeaseView acquire(@PathVariable long taskId, @Valid @RequestBody LeaseAcquireRequest request) {
        return taskService.acquireLease(taskId, request);
    }

    /** 本人凭当前未到期 token 续租，到期时刻顺延为当前时刻后 30 秒。 */
    @PostMapping("/{taskId}/leases/renew")
    public LeaseView renew(@PathVariable long taskId, @Valid @RequestBody LeaseRenewRequest request) {
        return taskService.renewLease(taskId, request);
    }

    /** 租约模式回执：必须携带 agentId 与 token。 */
    @PostMapping("/{taskId}/lease-receipt")
    public TaskView leaseReceipt(@PathVariable long taskId, @Valid @RequestBody LeaseReceiptRequest request) {
        return taskService.leaseReceipt(taskId, request);
    }

    /** 查询租约当前快照（只读，不自动领取、不延期）。 */
    @GetMapping("/{taskId}/lease")
    public LeaseView getLease(@PathVariable long taskId) {
        return taskService.getLease(taskId);
    }
}
