package com.example.starter.batch;

import com.example.starter.batch.dto.LabelDetailResponse;
import com.example.starter.batch.dto.LabelDiagnosticsResponse;
import com.example.starter.batch.dto.RegisterPlanRequest;
import com.example.starter.batch.dto.SealBoxesRequest;
import com.example.starter.batch.dto.VoidSealRequest;
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
 * 包装标签核销接口：包装计划登记、按标签号批量封箱、封箱作废与明细/历史/诊断查询。
 */
@RestController
@RequestMapping("/api/batches")
public class LabelController {

    private final LabelService service;

    public LabelController(LabelService service) {
        this.service = service;
    }

    /**
     * 登记包装计划：计划包装数量与连续标签号段（两端含），每批次一份，登记后不可改写。
     */
    @PostMapping("/{batchKey}/packaging-plan")
    public ResponseEntity<String> registerPlan(@PathVariable String batchKey,
                                               @Valid @RequestBody RegisterPlanRequest request) {
        return stored(service.registerPlan(batchKey, request));
    }

    /**
     * 批量封箱：先校验完整最终集合，任一标签失败整次回滚，不留下部分占用。
     */
    @PostMapping("/{batchKey}/seals")
    public ResponseEntity<String> sealBoxes(@PathVariable String batchKey,
                                            @Valid @RequestBody SealBoxesRequest request) {
        return stored(service.sealBoxes(batchKey, request));
    }

    /**
     * 作废封箱：仅未放行批次可作废；写入原因并释放数量与标签占用。
     */
    @PostMapping("/{batchKey}/seals/{sealKey}/void")
    public ResponseEntity<String> voidSeal(@PathVariable String batchKey,
                                           @PathVariable String sealKey,
                                           @Valid @RequestBody VoidSealRequest request) {
        return stored(service.voidSeal(batchKey, sealKey, request));
    }

    /**
     * 标签核销明细：计划 + 活跃封箱 + 汇总（实际值/要求值/差额）；只读。
     */
    @GetMapping("/{batchKey}/labels")
    public LabelDetailResponse detail(@PathVariable String batchKey) {
        return service.detail(batchKey);
    }

    /**
     * 标签核销历史：计划 + 全部封箱（含已作废）+ 汇总；只读。
     */
    @GetMapping("/{batchKey}/labels/history")
    public LabelDetailResponse history(@PathVariable String batchKey) {
        return service.history(batchKey);
    }

    /**
     * 标签核销诊断：计划/实际/差额、当前标签摘要与放行快照比对；只读。
     */
    @GetMapping("/{batchKey}/labels/diagnostics")
    public LabelDiagnosticsResponse diagnostics(@PathVariable String batchKey) {
        return service.diagnostics(batchKey);
    }

    private ResponseEntity<String> stored(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }
}
