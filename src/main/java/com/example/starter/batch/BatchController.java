package com.example.starter.batch;

import com.example.starter.batch.dto.ApproveRequest;
import com.example.starter.batch.dto.BatchHistoryResponse;
import com.example.starter.batch.dto.BatchResponse;
import com.example.starter.batch.dto.CreateBatchRequest;
import com.example.starter.batch.dto.LineageEntryResponse;
import com.example.starter.batch.dto.MergeRequest;
import com.example.starter.batch.dto.PotencyDiagnosticsResponse;
import com.example.starter.batch.dto.PotencyRevisionRequest;
import com.example.starter.batch.dto.RecallRequest;
import com.example.starter.batch.dto.SplitRequest;
import com.example.starter.batch.dto.SubmitTestRequest;
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
 * 生产批次隔离、检验、血缘、放行与效价衰减接口。
 */
@RestController
@RequestMapping("/api/batches")
public class BatchController {

    private final BatchService service;

    public BatchController(BatchService service) {
        this.service = service;
    }

    /**
     * 创建批次，初始状态 QUARANTINED；可整组登记数量与线性效价模型 v1。
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
     * 第二次批准按可注入时钟计算当前效价，低于最低放行阈值返回 422（含实际/要求/差额）。
     */
    @PostMapping("/{batchKey}/approvals")
    public ResponseEntity<String> approve(@PathVariable String batchKey,
                                          @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
                                          @RequestHeader(name = "X-Approval-Role", required = false) String role,
                                          @Valid @RequestBody ApproveRequest request) {
        return stored(service.approve(batchKey, actorId, role, request));
    }

    /**
     * 召回已放行/已拆分批次。
     */
    @PostMapping("/{batchKey}/recall")
    public ResponseEntity<String> recall(@PathVariable String batchKey,
                                         @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
                                         @Valid @RequestBody RecallRequest request) {
        return stored(service.recall(batchKey, actorId, request));
    }

    /**
     * 效价模型修订：只追加新版本，与检验/放行按提交顺序裁决。
     */
    @PostMapping("/{batchKey}/potency-revisions")
    public ResponseEntity<String> revisePotency(@PathVariable String batchKey,
                                                @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
                                                @Valid @RequestBody PotencyRevisionRequest request) {
        return stored(service.revisePotency(batchKey, actorId, request));
    }

    /**
     * 当前可用批次：不含已召回批次及召回祖先的后代。
     */
    @GetMapping("/available")
    public List<BatchResponse> available() {
        return service.listAvailable();
    }

    /**
     * 批次完整历史明细：批次概要、全部检验、全部批准、召回记录与效价固化计算。
     */
    @GetMapping("/{batchKey}/history")
    public BatchHistoryResponse history(@PathVariable String batchKey) {
        return service.history(batchKey);
    }

    /**
     * 效价只读诊断：当前模型、当前效价/阈值/余量、历史版本与固化记录；查询不修改状态。
     */
    @GetMapping("/{batchKey}/potency")
    public PotencyDiagnosticsResponse potency(@PathVariable String batchKey) {
        return service.potencyDiagnostics(batchKey);
    }

    /**
     * 拆分批次：仅当前可用的 RELEASED 父批可拆成 2～5 个全新子批，父批置为 SPLIT；
     * 登记数量/效价时按分摊数量守恒并固化子批初始效价。
     */
    @PostMapping("/{batchKey}/split")
    public ResponseEntity<String> split(@PathVariable String batchKey,
                                        @Valid @RequestBody SplitRequest request) {
        return stored(service.split(batchKey, request));
    }

    /**
     * 合批：2～5 个已放行父批按投入数量加权产生一个全新批次，任一父批效价不合格或数量不守恒整次回滚。
     */
    @PostMapping("/merge")
    public ResponseEntity<String> merge(@Valid @RequestBody MergeRequest request) {
        return stored(service.merge(request));
    }

    /**
     * 祖先查询：DAG 广度优先，含各批自身状态及导致不可用的召回祖先。
     */
    @GetMapping("/{batchKey}/ancestors")
    public List<LineageEntryResponse> ancestors(@PathVariable String batchKey) {
        return service.listAncestors(batchKey);
    }

    /**
     * 后代查询：按血缘创建顺序广度优先展开，含各批自身状态及导致不可用的召回祖先。
     */
    @GetMapping("/{batchKey}/descendants")
    public List<LineageEntryResponse> descendants(@PathVariable String batchKey) {
        return service.listDescendants(batchKey);
    }

    private ResponseEntity<String> stored(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }
}
