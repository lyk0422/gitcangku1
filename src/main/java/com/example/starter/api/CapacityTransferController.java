package com.example.starter.api;

import com.example.starter.api.dto.CapacityConfigRequest;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.RouteActivateRequest;
import com.example.starter.api.dto.TransferEvidenceDto;
import com.example.starter.api.dto.TransferPreviewRequest;
import com.example.starter.api.dto.TransferPreviewResult;
import com.example.starter.api.dto.TransferRequest;
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
 * 空域容量账本与时空桶转配 API。
 */
@RestController
@RequestMapping("/api/airspace")
public class CapacityTransferController {

    private final CapacityTransferService service;

    public CapacityTransferController(CapacityTransferService service) {
        this.service = service;
    }

    /** 新建或调整空域单元 15 分钟桶容量上限。 */
    @PostMapping("/capacity-configs")
    public ResponseEntity<MutationResponse> configureCapacity(
            @Valid @RequestBody CapacityConfigRequest request) {
        return ResponseEntity.ok(service.configureCapacity(request));
    }

    /** 激活审查通过的航线版本（生成容量占用）。 */
    @PostMapping("/routes/activate")
    public ResponseEntity<MutationResponse> activateRoute(
            @Valid @RequestBody RouteActivateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.activateRoute(request));
    }

    /** 预览容量转配（只读，返回版本、容量余量与违规明细）。 */
    @PostMapping("/capacity-transfers/preview")
    public TransferPreviewResult previewTransfer(
            @Valid @RequestBody TransferPreviewRequest request) {
        return service.previewTransfer(request);
    }

    /** 激活容量转配单（单事务校验并应用，任一失败整体回滚）。 */
    @PostMapping("/capacity-transfers")
    public ResponseEntity<MutationResponse> applyTransfer(
            @Valid @RequestBody TransferRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.applyTransfer(request));
    }

    /** 查询转配单证据（只读，按桶、航线稳定排序）。 */
    @GetMapping("/capacity-transfers/{transferKey}")
    public TransferEvidenceDto getTransfer(@PathVariable String transferKey) {
        return service.getTransfer(transferKey);
    }
}
