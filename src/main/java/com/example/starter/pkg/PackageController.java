package com.example.starter.pkg;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.common.StoredResponse;
import com.example.starter.pkg.PackageDtos.AnomalyRequest;
import com.example.starter.pkg.PackageDtos.HandoverRequest;
import com.example.starter.pkg.PackageDtos.ResealRequest;
import com.example.starter.pkg.PackageDtos.SealRequest;

/**
 * 封存包入口：封存、交接、封签异常、重新封存。
 */
@RestController
@RequestMapping("/api/packages")
public class PackageController {

    private final PackageService service;

    public PackageController(PackageService service) {
        this.service = service;
    }

    @PostMapping("/seal")
    public ResponseEntity<String> seal(@Valid @RequestBody SealRequest req) {
        return service.seal(req).toEntity();
    }

    @PostMapping("/{packageNo}/handover")
    public ResponseEntity<String> handover(@PathVariable String packageNo, @Valid @RequestBody HandoverRequest req) {
        return service.handover(packageNo, req).toEntity();
    }

    @PostMapping("/{packageNo}/anomaly")
    public ResponseEntity<String> anomaly(@PathVariable String packageNo, @Valid @RequestBody AnomalyRequest req) {
        return service.reportAnomaly(packageNo, req).toEntity();
    }

    @PostMapping("/reseal")
    public ResponseEntity<String> reseal(@Valid @RequestBody ResealRequest req) {
        return service.reseal(req).toEntity();
    }
}