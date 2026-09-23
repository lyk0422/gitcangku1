package com.example.starter.calibration.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.CreateStandardRequest;
import com.example.starter.calibration.api.dto.CreateStandardVersionRequest;
import com.example.starter.calibration.api.dto.StandardResponse;
import com.example.starter.calibration.api.dto.StandardVersionResponse;
import com.example.starter.calibration.model.Standard;
import com.example.starter.calibration.model.StandardVersion;
import com.example.starter.calibration.repo.StandardRepository;
import com.example.starter.calibration.repo.StandardVersionRepository;
import com.example.starter.calibration.service.StandardLineageService;

/**
 * 标准器血缘接口：创建标准器、创建标准器版本（建立血缘）、查询。
 */
@RestController
@RequestMapping("/api/standards")
public class StandardController {

    private final StandardLineageService lineage;
    private final StandardRepository standards;
    private final StandardVersionRepository versions;

    public StandardController(StandardLineageService lineage,
                              StandardRepository standards,
                              StandardVersionRepository versions) {
        this.lineage = lineage;
        this.standards = standards;
        this.versions = versions;
    }

    /**
     * 创建标准器：201；standardId 重复 409。
     */
    @PostMapping
    public ResponseEntity<StandardResponse> createStandard(@RequestBody CreateStandardRequest request) {
        long id = lineage.createStandard(request.standardId(), request.name());
        Standard standard = standards.findByStandardId(request.standardId().trim()).orElseThrow();
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(standard));
    }

    /**
     * 创建标准器版本并建立血缘：201；血缘成环或子窗口超出父窗口 422；版本键重复 409。
     */
    @PostMapping("/versions")
    public ResponseEntity<StandardVersionResponse> createVersion(
            @RequestBody CreateStandardVersionRequest request) {
        StandardVersion version = lineage.createVersion(request.versionKey(), request.standardId(),
                request.parentVersionKey(), request.validFrom(), request.validTo(), request.certificateNo());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(version));
    }

    /**
     * 查询全部标准器版本（按 standardId、id 稳定排序）。
     */
    @GetMapping("/versions")
    public List<StandardVersionResponse> listVersions() {
        return versions.findAll().stream().map(StandardController::toResponse).toList();
    }

    private static StandardResponse toResponse(Standard standard) {
        return new StandardResponse(standard.id(), standard.standardId(), standard.name(),
                standard.createdAt());
    }

    private static StandardVersionResponse toResponse(StandardVersion v) {
        return new StandardVersionResponse(v.id(), v.versionKey(), v.standardId(), v.parentVersionId(),
                v.validFrom(), v.validTo(), v.certificateNo(), v.status().name(), v.createdAt());
    }
}
