package com.example.starter.batch;

import com.example.starter.batch.dto.LabelDetailResponse;
import com.example.starter.batch.dto.LabelDiagnosisResponse;
import com.example.starter.batch.dto.LabelHistoryResponse;
import com.example.starter.batch.dto.ReleaseSnapshotResponse;
import com.example.starter.batch.dto.RegisterLabelPlanRequest;
import com.example.starter.batch.dto.SealBoxesRequest;
import com.example.starter.batch.dto.VoidSealsRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 包装标签核销接口：计划登记、批量封箱、批量作废、当前明细、历史、放行快照与差异诊断。
 * 所有查询接口只读，不改变状态。
 */
@RestController
@RequestMapping("/api/batches/{batchKey}")
public class LabelController {

    private final LabelService service;

    public LabelController(LabelService service) {
        this.service = service;
    }

    /**
     * 登记包装标签计划（计划数量与连续标签号段）。
     */
    @PostMapping("/label-plan")
    public ResponseEntity<String> registerPlan(@PathVariable String batchKey,
                                               @Valid @RequestBody RegisterLabelPlanRequest request) {
        return stored(service.registerPlan(batchKey, request));
    }

    /**
     * 批量封箱：按标签号提交封箱记录，任一标签失败整次回滚。
     */
    @PostMapping("/seals")
    public ResponseEntity<String> sealBoxes(@PathVariable String batchKey,
                                            @Valid @RequestBody SealBoxesRequest request) {
        return stored(service.sealBoxes(batchKey, request));
    }

    /**
     * 批量作废：仅未放行批次可作废，写入原因并释放数量与标签占用。
     */
    @PostMapping("/seals/void")
    public ResponseEntity<String> voidSeals(@PathVariable String batchKey,
                                            @Valid @RequestBody VoidSealsRequest request) {
        return stored(service.voidSeals(batchKey, request));
    }

    /**
     * 标签核销当前明细：计划、守恒汇总与当前生效封箱。
     */
    @GetMapping("/labels")
    public LabelDetailResponse detail(@PathVariable String batchKey) {
        return service.detail(batchKey);
    }

    /**
     * 标签封箱完整历史：全部封箱版本（含已作废）。
     */
    @GetMapping("/labels/history")
    public LabelHistoryResponse history(@PathVariable String batchKey) {
        return service.history(batchKey);
    }

    /**
     * 放行标签快照查询：固化的计划数量、实际封箱数量、已用标签排序摘要与每个封箱版本。
     */
    @GetMapping("/labels/snapshot")
    public ReleaseSnapshotResponse snapshot(@PathVariable String batchKey) {
        return service.snapshot(batchKey);
    }

    /**
     * 标签差异诊断：当前核销状态、放行快照及二者差异；只读，不改变状态。
     */
    @GetMapping("/labels/diagnosis")
    public LabelDiagnosisResponse diagnose(@PathVariable String batchKey) {
        return service.diagnose(batchKey);
    }

    private ResponseEntity<String> stored(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }
}
