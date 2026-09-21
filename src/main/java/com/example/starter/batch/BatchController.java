package com.example.starter.batch;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 生产批次隔离与放行 API。不连接生产线、LIMS 或外部仓库系统。
 */
@RestController
@RequestMapping("/api/batches")
public class BatchController {

    private final BatchService service;

    public BatchController(BatchService service) {
        this.service = service;
    }

    /** 创建批次，初始状态 QUARANTINED。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BatchResponse createBatch(@Valid @RequestBody CreateBatchRequest request) {
        return service.createBatch(request);
    }

    /** 提交检验结果。 */
    @PostMapping("/{batchKey}/tests")
    public TestResultResponse submitTest(@PathVariable String batchKey,
                                         @Valid @RequestBody SubmitTestRequest request) {
        return service.submitTest(batchKey, request);
    }

    /** 放行批准；批准人与角色由请求头提供。 */
    @PostMapping("/{batchKey}/approvals")
    public ApprovalResponse approve(@PathVariable String batchKey,
                                    @RequestHeader("X-Actor-Id") String actorId,
                                    @RequestHeader("X-Approval-Role") ApprovalRole role,
                                    @Valid @RequestBody ApproveRequest request) {
        return service.approve(batchKey, actorId, role, request.commandKey());
    }

    /** 召回已放行批次；操作人由请求头提供。 */
    @PostMapping("/{batchKey}/recalls")
    public RecallResponse recall(@PathVariable String batchKey,
                                 @RequestHeader("X-Actor-Id") String actorId,
                                 @Valid @RequestBody RecallRequest request) {
        return service.recall(batchKey, actorId, request);
    }

    /** 当前可用批次（不含 REJECTED 与 RECALLED）。 */
    @GetMapping("/available")
    public List<BatchResponse> listAvailable() {
        return service.listAvailable();
    }

    /** 批次完整历史明细。 */
    @GetMapping("/{batchKey}")
    public BatchDetailResponse detail(@PathVariable String batchKey) {
        return service.detail(batchKey);
    }
}
