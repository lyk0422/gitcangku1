package com.example.starter.batch;

import com.example.starter.batch.dto.AdvanceGenerationRequest;
import com.example.starter.batch.dto.ApproveRequest;
import com.example.starter.batch.dto.BatchHistoryResponse;
import com.example.starter.batch.dto.BatchResponse;
import com.example.starter.batch.dto.CreateBatchRequest;
import com.example.starter.batch.dto.LineageEntryResponse;
import com.example.starter.batch.dto.MergeLookupResponse;
import com.example.starter.batch.dto.MergeRequest;
import com.example.starter.batch.dto.RecallRequest;
import com.example.starter.batch.dto.SplitRequest;
import com.example.starter.batch.dto.SubmitTestRequest;
import com.example.starter.batch.dto.UpdateAllergensRequest;
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

    public BatchController(BatchService service) {
        this.service = service;
    }

    /**
     * 创建批次，初始状态 QUARANTINED，过敏原集合与清洁代次在保存时规范化。
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
     * 保存（整体替换）批次过敏原集合：服务端去重并字典序稳定排序。
     */
    @PostMapping("/{batchKey}/allergens")
    public ResponseEntity<String> saveAllergens(@PathVariable String batchKey,
                                                @Valid @RequestBody UpdateAllergensRequest request) {
        return stored(service.saveAllergens(batchKey, request));
    }

    /**
     * 推进批次所在生产线的清洁代次；只能严格向前，与合批串行。
     */
    @PostMapping("/{batchKey}/cleaning-generation")
    public ResponseEntity<String> advanceGeneration(@PathVariable String batchKey,
                                                    @Valid @RequestBody AdvanceGenerationRequest request) {
        return stored(service.advanceGeneration(batchKey, request));
    }

    /**
     * 合批：多个已放行、过敏原集合相同且处于同一有效清洁代次的父批合并为一个子批；
     * 前置条件不满足返回 422，不生成子批且不改变任何父批。
     */
    @PostMapping("/merge")
    public ResponseEntity<String> merge(@Valid @RequestBody MergeRequest request) {
        return stored(service.merge(request));
    }

    /**
     * 按合批 requestId 查询首次子批快照或全部 422 拒绝原因。
     */
    @GetMapping("/merge/{requestId}")
    public MergeLookupResponse mergeLookup(@PathVariable String requestId) {
        return service.lookupMerge(requestId);
    }

    /**
     * 当前可用批次：不含已召回、已拆分、已合批消耗批次。
     */
    @GetMapping("/available")
    public List<BatchResponse> available() {
        return service.listAvailable();
    }

    /**
     * 批次完整历史明细：批次概要、全部检验、全部批准与召回记录，合批子批另含冻结依据。
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
     * 后代查询：按拆分/合批创建顺序展开，含各批自身状态及导致不可用的召回祖先。
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
