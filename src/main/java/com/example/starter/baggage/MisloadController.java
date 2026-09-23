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

import com.example.starter.baggage.MisloadDtos.ConfirmRerouteRequest;
import com.example.starter.baggage.MisloadDtos.ConfirmRerouteResponse;
import com.example.starter.baggage.MisloadDtos.PreviewRerouteRequest;
import com.example.starter.baggage.MisloadDtos.PreviewRerouteResponse;
import com.example.starter.baggage.MisloadDtos.RegisterMisloadRequest;
import com.example.starter.baggage.MisloadDtos.RegisterMisloadResponse;

/**
 * 错装行李批次追回与剩余路径原子改派 REST 入口（骨架，待逐方法实现）。
 */
@RestController
@RequestMapping("/api/misloads")
public class MisloadController {

    private final MisloadService misloadService;

    public MisloadController(MisloadService misloadService) {
        this.misloadService = misloadService;
    }

    /** 登记错装批次。 */
    @PostMapping
    public ResponseEntity<RegisterMisloadResponse> register(
            @Valid @RequestBody RegisterMisloadRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(misloadService.register(request));
    }

    /** 预览恢复路径。 */
    @PostMapping("/{incidentKey}/reroute/preview")
    public PreviewRerouteResponse preview(@PathVariable String incidentKey,
                                          @Valid @RequestBody PreviewRerouteRequest request) {
        return misloadService.preview(incidentKey, request);
    }

    /** 确认原子改派。 */
    @PostMapping("/{incidentKey}/reroute/confirm")
    public ConfirmRerouteResponse confirm(@PathVariable String incidentKey,
                                          @Valid @RequestBody ConfirmRerouteRequest request) {
        return misloadService.confirm(incidentKey, request);
    }

    /** 错装批次只读查询。 */
    @GetMapping("/{incidentKey}")
    public com.example.starter.baggage.MisloadDtos.MisloadIncidentResponse getIncident(
            @PathVariable String incidentKey) {
        return misloadService.getIncident(incidentKey);
    }

    /** 逐件路径血缘只读查询。 */
    @GetMapping("/bags/{bagTag}/lineage")
    public com.example.starter.baggage.MisloadDtos.BagPathLineageResponse getPathLineage(
            @PathVariable String bagTag) {
        return misloadService.getPathLineage(bagTag);
    }
}
