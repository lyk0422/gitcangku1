package com.example.starter.batch;

import com.example.starter.batch.dto.ApproveRequest;
import com.example.starter.batch.dto.BatchHistoryResponse;
import com.example.starter.batch.dto.BatchResponse;
import com.example.starter.batch.dto.CaseResponse;
import com.example.starter.batch.dto.CreateBatchRequest;
import com.example.starter.batch.dto.LabelReconciliationResponse;
import com.example.starter.batch.dto.LabelSummaryResponse;
import com.example.starter.batch.dto.LineageEntryResponse;
import com.example.starter.batch.dto.RecallRequest;
import com.example.starter.batch.dto.RegisterLabelPlanRequest;
import com.example.starter.batch.dto.SealCasesRequest;
import com.example.starter.batch.dto.SplitRequest;
import com.example.starter.batch.dto.SubmitTestRequest;
import com.example.starter.batch.dto.VoidCaseRequest;
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

    private ResponseEntity<String> stored(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }

    /**
     * 登记包装标签计划：计划包装数量与左闭右开标签号段；每批次一份，不可改写。
     */
    @PostMapping("/{batchKey}/label-plan")
    public ResponseEntity<String> registerLabelPlan(@PathVariable String batchKey,
                                                    @Valid @RequestBody RegisterLabelPlanRequest request) {
        return stored(service.registerLabelPlan(batchKey, request));
    }

    /**
     * 批量封箱：按标签号提交封箱记录，任一标签失败整次回滚。
     */
    @PostMapping("/{batchKey}/cases")
    public ResponseEntity<String> sealCases(@PathVariable String batchKey,
                                            @Valid @RequestBody SealCasesRequest request) {
        return stored(service.sealCases(batchKey, request));
    }

    /**
     * 作废封箱：仅未放行批次允许，写入原因并释放数量与标签占用。
     */
    @PostMapping("/{batchKey}/cases/{caseKey}/void")
    public ResponseEntity<String> voidCase(@PathVariable String batchKey,
                                           @PathVariable String caseKey,
                                           @Valid @RequestBody VoidCaseRequest request) {
        return stored(service.voidCase(batchKey, caseKey, request));
    }

    /**
     * 封箱明细查询：全部封箱记录（含已作废），按提交顺序。
     */
    @GetMapping("/{batchKey}/cases")
    public List<CaseResponse> listCases(@PathVariable String batchKey) {
        return service.listCases(batchKey);
    }

    /**
     * 标签核销汇总：计划、已封数量、差额、已用标签规范排序与放行快照。
     */
    @GetMapping("/{batchKey}/label-summary")
    public LabelSummaryResponse labelSummary(@PathVariable String batchKey) {
        return service.labelSummary(batchKey);
    }

    /**
     * 标签核销诊断：比对放行快照或计划与当前封箱状态，返回差异列表。
     */
    @GetMapping("/{batchKey}/label-reconciliation")
    public LabelReconciliationResponse labelReconciliation(@PathVariable String batchKey) {
        return service.labelReconciliation(batchKey);
    }
}
