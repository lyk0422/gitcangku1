package com.example.starter.batch;

import com.example.starter.batch.dto.ApproveRequest;
import com.example.starter.batch.dto.BatchHistoryResponse;
import com.example.starter.batch.dto.BatchResponse;
import com.example.starter.batch.dto.CompositionVersionResponse;
import com.example.starter.batch.dto.CreateBatchRequest;
import com.example.starter.batch.dto.LineageEntryResponse;
import com.example.starter.batch.dto.LineageSnapshotResponse;
import com.example.starter.batch.dto.MergeDiagnoseRequest;
import com.example.starter.batch.dto.MergeDiagnoseResponse;
import com.example.starter.batch.dto.MergeRequest;
import com.example.starter.batch.dto.RecallRequest;
import com.example.starter.batch.dto.ReviseCompositionRequest;
import com.example.starter.batch.dto.RiskResponse;
import com.example.starter.batch.dto.SplitRequest;
import com.example.starter.batch.dto.SubmitTestRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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

    public BatchController(BatchService service) {
        this.service = service;
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
     * 成分修订：维护过敏原代码集合与隔离级别，追加不可变成分版本；
     * expectedVersion 乐观校验，未知代码或空级别 422，allergenKey 幂等。
     */
    @PutMapping("/{batchKey}/composition")
    public ResponseEntity<String> reviseComposition(@PathVariable String batchKey,
                                                    @Valid @RequestBody ReviseCompositionRequest request) {
        return stored(service.reviseComposition(batchKey, request));
    }

    /**
     * 合批：多个 RELEASED 来源批次在目标容器内合并为全新目标批次；
     * 先校验全部来源与兼容矩阵，任一不兼容整次失败并回滚全部血缘与库存。
     */
    @PostMapping("/merge")
    public ResponseEntity<String> merge(@Valid @RequestBody MergeRequest request) {
        return stored(service.merge(request));
    }

    /**
     * 合批兼容诊断：只校验不执行，返回各来源状态与缺失兼容级别对。
     */
    @PostMapping("/merge/diagnose")
    public MergeDiagnoseResponse diagnoseMerge(@Valid @RequestBody MergeDiagnoseRequest request) {
        return service.diagnoseMerge(request);
    }

    /**
     * 成分版本查询：批次全部不可变成分版本及各自检验状态。
     */
    @GetMapping("/{batchKey}/compositions")
    public List<CompositionVersionResponse> compositions(@PathVariable String batchKey) {
        return service.listCompositions(batchKey);
    }

    /**
     * 风险查询：过敏原风险状态与原放行快照。
     */
    @GetMapping("/{batchKey}/risk")
    public RiskResponse risk(@PathVariable String batchKey) {
        return service.risk(batchKey);
    }

    /**
     * 血缘快照：当前成分版本、跨拆分与合批的传递祖先集合、直接合批来源。
     */
    @GetMapping("/{batchKey}/lineage")
    public LineageSnapshotResponse lineageSnapshot(@PathVariable String batchKey) {
        return service.lineageSnapshot(batchKey);
    }

    private ResponseEntity<String> stored(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }
}
