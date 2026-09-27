package com.example.starter.batch;

import com.example.starter.batch.dto.LabelDiagnosisResponse;
import com.example.starter.batch.dto.PackRequest;
import com.example.starter.batch.dto.PackingDetailResponse;
import com.example.starter.batch.dto.PackingHistoryResponse;
import com.example.starter.batch.dto.RegisterPlanRequest;
import com.example.starter.batch.dto.VoidPackingRequest;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 批次标签核销接口：包装计划登记、按标签批量封箱、封箱作废，以及只读的明细/历史/差异诊断。
 */
@RestController
@RequestMapping("/api/batches")
public class PackingController {

    private final PackingService service;

    public PackingController(PackingService service) {
        this.service = service;
    }

    /**
     * 登记包装计划（计划包装数量 + 连续标签号段）；每批次至多一份。
     */
    @PostMapping("/{batchKey}/packing-plan")
    public ResponseEntity<String> registerPlan(@PathVariable String batchKey,
                                               @Valid @RequestBody RegisterPlanRequest request) {
        return stored(service.registerPlan(batchKey, request));
    }

    /**
     * 按标签号批量封箱；先校验完整最终集合，任一标签失败整次回滚。
     */
    @PostMapping("/{batchKey}/packings")
    public ResponseEntity<String> pack(@PathVariable String batchKey,
                                       @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
                                       @Valid @RequestBody PackRequest request) {
        return stored(service.pack(batchKey, actorId, request));
    }

    /**
     * 作废指定封箱版本；仅未放行批次可作废，释放数量与标签占用并保留历史。
     */
    @PostMapping("/{batchKey}/packings/{packingId}/void")
    public ResponseEntity<String> voidPacking(@PathVariable String batchKey,
                                              @PathVariable long packingId,
                                              @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
                                              @Valid @RequestBody VoidPackingRequest request) {
        return stored(service.voidPacking(batchKey, actorId, packingId, request));
    }

    /**
     * 包装/标签核销明细：计划、实际数量、标签计数、全部封箱版本与放行快照；只读不改状态。
     */
    @GetMapping("/{batchKey}/packing")
    public PackingDetailResponse detail(@PathVariable String batchKey) {
        return service.detail(batchKey);
    }

    /**
     * 封箱/作废历史：UTC 时间窗 [from, to) 左闭右开，参数可省略；只读不改状态。
     */
    @GetMapping("/{batchKey}/packing/history")
    public PackingHistoryResponse history(@PathVariable String batchKey,
                                          @RequestParam(name = "from", required = false) Instant from,
                                          @RequestParam(name = "to", required = false) Instant to) {
        return service.history(batchKey, from, to);
    }

    /**
     * 标签差异诊断：放行前核对核销完整性，放行后比对固化快照，保留差异诊断；只读不改状态。
     */
    @GetMapping("/{batchKey}/packing/diagnosis")
    public LabelDiagnosisResponse diagnose(@PathVariable String batchKey) {
        return service.diagnose(batchKey);
    }

    private ResponseEntity<String> stored(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }
}
