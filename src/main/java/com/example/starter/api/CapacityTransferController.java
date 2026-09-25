package com.example.starter.api;

import com.example.starter.api.dto.BucketCapacityDto;
import com.example.starter.api.dto.CapacityConfigRequest;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupancyPlanRequest;
import com.example.starter.api.dto.TransferActivateRequest;
import com.example.starter.api.dto.TransferEvidenceResult;
import com.example.starter.api.dto.TransferPreviewResult;
import com.example.starter.service.CapacityService;
import com.example.starter.service.CapacityTransferService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 容量账本与航路时空桶闭环原子转配 API。
 */
@RestController
@RequestMapping("/api/airspace")
public class CapacityTransferController {

    private final CapacityService capacityService;
    private final CapacityTransferService transferService;

    public CapacityTransferController(CapacityService capacityService,
                                      CapacityTransferService transferService) {
        this.capacityService = capacityService;
        this.transferService = transferService;
    }

    /** 管理员配置时空桶容量上限。 */
    @PostMapping("/capacity/buckets")
    public ResponseEntity<MutationResponse> configureCapacity(
            @Valid @RequestBody CapacityConfigRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(capacityService.configure(request));
    }

    /** 查询时空桶容量与当前全量占用（只读）。 */
    @GetMapping("/capacity/buckets")
    public BucketCapacityDto getBucket(@RequestParam int cellX,
                                       @RequestParam int cellY,
                                       @RequestParam long bucketStart) {
        return capacityService.getBucket(cellX, cellY, bucketStart);
    }

    /** 运营方登记当前航线版本穿越序列。 */
    @PostMapping("/capacity/occupancy-plans")
    public ResponseEntity<MutationResponse> registerPlan(
            @Valid @RequestBody OccupancyPlanRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(capacityService.registerPlan(request));
    }

    /** 转配预览（只读）：返回版本、容量余量与违规明细。 */
    @PostMapping("/capacity/transfers/preview")
    public TransferPreviewResult preview(@Valid @RequestBody TransferActivateRequest request) {
        return transferService.preview(request);
    }

    /** 激活转配：单事务闭环原子替换全部占用并逐航线增版。 */
    @PostMapping("/capacity/transfers")
    public ResponseEntity<MutationResponse> activate(
            @Valid @RequestBody TransferActivateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(transferService.activate(request));
    }

    /** 按 transferKey 查询冻结证据（只读，按航线、桶稳定排序）。 */
    @GetMapping("/capacity/transfers/{transferKey}/evidence")
    public TransferEvidenceResult getEvidence(@PathVariable String transferKey) {
        return transferService.getEvidence(transferKey);
    }
}
