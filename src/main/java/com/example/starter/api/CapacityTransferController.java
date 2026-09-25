package com.example.starter.api;

import com.example.starter.api.dto.CapacityConfigRequest;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.RouteActivateRequest;
import com.example.starter.api.dto.RouteDeactivateRequest;
import com.example.starter.api.dto.TransferActivateRequest;
import com.example.starter.api.dto.TransferEvidenceDto;
import com.example.starter.api.dto.TransferPreviewRequest;
import com.example.starter.api.dto.TransferPreviewResponse;
import com.example.starter.service.CapacityTransferService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 航路时空桶容量账本与闭环原子转配 API。
 */
@RestController
@RequestMapping("/api/airspace/capacity")
public class CapacityTransferController {

    private final CapacityTransferService service;

    public CapacityTransferController(CapacityTransferService service) {
        this.service = service;
    }

    /** 配置或调整时空桶容量上限。 */
    @PostMapping("/configs")
    public ResponseEntity<MutationResponse> configureCapacity(
            @Valid @RequestBody CapacityConfigRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.configureCapacity(request));
    }

    /** 激活审查通过的航线版本，按穿越序列占用时空桶容量。 */
    @PostMapping("/routes/activate")
    public ResponseEntity<MutationResponse> activateRoute(
            @Valid @RequestBody RouteActivateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.activateRoute(request));
    }

    /** 停用航线，移除全部时空桶占用。 */
    @PostMapping("/routes/deactivate")
    public ResponseEntity<MutationResponse> deactivateRoute(
            @Valid @RequestBody RouteDeactivateRequest request) {
        return ResponseEntity.ok(service.deactivateRoute(request));
    }

    /** 预览容量转配（只读，按完整后态计算版本、容量余量与违规明细）。 */
    @PostMapping("/transfers/preview")
    public TransferPreviewResponse previewTransfer(
            @Valid @RequestBody TransferPreviewRequest request) {
        return service.previewTransfer(request);
    }

    /** 激活容量转配（单事务原子生效，失败整体回滚）。 */
    @PostMapping("/transfers")
    public ResponseEntity<MutationResponse> activateTransfer(
            @Valid @RequestBody TransferActivateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.activateTransfer(request));
    }

    /** 查询转配冻结证据（只读，按桶、航线稳定排序）。 */
    @GetMapping("/transfers/{transferKey}")
    public TransferEvidenceDto getTransferEvidence(@PathVariable String transferKey) {
        return service.getTransferEvidence(transferKey);
    }
}
