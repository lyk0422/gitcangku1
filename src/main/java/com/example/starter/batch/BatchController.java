package com.example.starter.batch;

import com.example.starter.batch.dto.ApproveRequest;
import com.example.starter.batch.dto.BatchHistoryResponse;
import com.example.starter.batch.dto.BatchResponse;
import com.example.starter.batch.dto.CreateBatchRequest;
import com.example.starter.batch.dto.DisposeRequest;
import com.example.starter.batch.dto.LineageEntryResponse;
import com.example.starter.batch.dto.RecallRequest;
import com.example.starter.batch.dto.SplitRequest;
import com.example.starter.batch.dto.SubmitTestRequest;
import com.example.starter.batch.dto.SubmitYieldRequest;
import com.example.starter.batch.dto.YieldCurrentResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 生产批次隔离与放行接口。
 */
@RestController
@RequestMapping("/api/batches")
public class BatchController {

    private final BatchService service;
    private final YieldService yieldService;

    public BatchController(BatchService service, YieldService yieldService) {
        this.service = service;
        this.yieldService = yieldService;
    }

    /**
     * 创建批次，初始状态 QUARANTINED。
     */
    @PostMapping
    public ResponseEntity<String> create(@Valid @RequestBody CreateBatchRequest request) {
        return stored(service.createBatch(request));
    }

    /**
     * 提交检验结果。
     */
    @PostMapping("/{batchKey}/tests")
    public ResponseEntity<String> submitTest(@PathVariable String batchKey,
                                             @Valid @RequestBody SubmitTestRequest request) {
        return stored(service.submitTest(batchKey, request));
    }

    /**
     * 提交放行批准；X-Actor-Id 为批准人，X-Approval-Role 为 QUALITY 或 OPERATIONS。
     */
    @PostMapping("/{batchKey}/approvals")
    public ResponseEntity<String> approve(@PathVariable String batchKey,
                                          @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
                                          @RequestHeader(name = "X-Approval-Role", required = false) String role,
                                          @Valid @RequestBody ApproveRequest request) {
        return stored(service.approve(batchKey, actorId, role, request));
    }

    /**
     * 召回已放行批次。
     */
    @PostMapping("/{batchKey}/recall")
    public ResponseEntity<String> recall(@PathVariable String batchKey,
                                         @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
                                         @Valid @RequestBody RecallRequest request) {
        return stored(service.recall(batchKey, actorId, request));
    }

    /**
     * 当前可用批次：不含已召回批次。
     */
    @GetMapping("/available")
    public List<BatchResponse> available() {
        return service.listAvailable();
    }

    /**
     * 批次完整历史明细：批次概要、全部检验、全部批准与召回记录。
     */
    @GetMapping("/{batchKey}/history")
    public BatchHistoryResponse history(@PathVariable String batchKey) {
        return service.history(batchKey);
    }

    /**
     * 拆分批次：仅当前可用的 RELEASED 父批可拆成 2～5 个全新子批，父批置为 SPLIT。
     */
    @PostMapping("/{batchKey}/split")
    public ResponseEntity<String> split(@PathVariable String batchKey,
                                        @Valid @RequestBody SplitRequest request) {
        return stored(service.split(batchKey, request));
    }

    /**
     * 祖先查询：从直接父批到根，含各批自身状态及导致不可用的召回祖先。
     */
    @GetMapping("/{batchKey}/ancestors")
    public List<LineageEntryResponse> ancestors(@PathVariable String batchKey) {
        return service.listAncestors(batchKey);
    }

    /**
     * 后代查询：按拆分创建顺序展开，含各批自身状态及导致不可用的召回祖先。
     */
    @GetMapping("/{batchKey}/descendants")
    public List<LineageEntryResponse> descendants(@PathVariable String batchKey) {
        return service.listDescendants(batchKey);
    }

    /**
     * 提交产率平衡核算：投料、合格产出、损耗、报废四位数量（三位小数），返回偏差与门禁状态。
     */
    @PostMapping("/{batchKey}/yields")
    public ResponseEntity<String> submitYield(@PathVariable String batchKey,
                                              @Valid @RequestBody SubmitYieldRequest request) {
        return stored(yieldService.submitYield(batchKey, request));
    }

    /**
     * 处置人确认超限偏差版本；X-Actor-Id 为处置人，两名不同处置人确认同一版本后解除门禁。
     */
    @PostMapping("/{batchKey}/yields/disposals")
    public ResponseEntity<String> dispose(@PathVariable String batchKey,
                                          @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
                                          @Valid @RequestBody DisposeRequest request) {
        return stored(yieldService.dispose(batchKey, actorId, request));
    }

    /**
     * 产率当前查询：最新版本四类数量、偏差、冻结上限与处置状态。
     */
    @GetMapping("/{batchKey}/yields/current")
    public YieldCurrentResponse currentYield(@PathVariable String batchKey) {
        return yieldService.current(batchKey);
    }

    private ResponseEntity<String> stored(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }
}
