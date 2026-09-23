package com.example.starter.baggage;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.baggage.RepackDtos.ActivateRepackRequest;
import com.example.starter.baggage.RepackDtos.ContainerPackRequest;
import com.example.starter.baggage.RepackDtos.CreateRepackRequest;

/**
 * 行李容器与重封单 REST 入口。
 */
@RestController
@RequestMapping("/api")
public class RepackController {

    private final RepackService repackService;

    public RepackController(RepackService repackService) {
        this.repackService = repackService;
    }

    /** 封装容器。 */
    @PostMapping("/containers")
    public ResponseEntity<RepackDtos.ContainerResponse> packContainer(
            @Valid @RequestBody ContainerPackRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(repackService.packContainer(request));
    }

    /** 创建重封单（仅预览）。 */
    @PostMapping("/repacks")
    public ResponseEntity<RepackDtos.RepackPreviewResponse> createRepack(
            @Valid @RequestBody CreateRepackRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(repackService.createRepack(request));
    }

    /** 双人激活重封单。 */
    @PostMapping("/repacks/{repackKey}/activate")
    public RepackDtos.RepackActivatedResponse activateRepack(
            @PathVariable String repackKey, @Valid @RequestBody ActivateRepackRequest request) {
        return repackService.activateRepack(repackKey, request);
    }

    /** 重封单详情查询。 */
    @GetMapping("/repacks/{repackKey}")
    public RepackDtos.RepackDetailResponse getRepack(@PathVariable String repackKey) {
        return repackService.getRepack(repackKey);
    }

    /** 重封证据查询：只读、稳定排序。 */
    @GetMapping("/repacks/{repackKey}/evidence")
    public RepackDtos.RepackEvidenceResponse getEvidence(@PathVariable String repackKey) {
        return repackService.getEvidence(repackKey);
    }

    /** 容器详情查询。 */
    @GetMapping("/containers/{containerNo}")
    public RepackDtos.ContainerResponse getContainer(@PathVariable String containerNo) {
        return repackService.getContainer(containerNo);
    }
}
