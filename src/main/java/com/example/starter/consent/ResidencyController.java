package com.example.starter.consent;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.consent.dto.GrantResidencyView;
import com.example.starter.consent.dto.MigrationRequest;
import com.example.starter.consent.dto.MigrationResponse;
import com.example.starter.consent.dto.MigrationView;
import com.example.starter.consent.dto.RegionRegisterRequest;
import com.example.starter.consent.dto.RegionResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * 数据驻留 API：区域登记、驻留迁移与诊断查询。查询类端点只读，不改变状态。
 */
@Validated
@RestController
@RequestMapping("/api/v1/residency")
public class ResidencyController {

    private final ResidencyService residencyService;

    public ResidencyController(ResidencyService residencyService) {
        this.residencyService = residencyService;
    }

    @PostMapping("/regions")
    public RegionResponse registerRegion(@Valid @RequestBody RegionRegisterRequest request) {
        return residencyService.registerRegion(request);
    }

    @GetMapping("/regions")
    public List<RegionResponse> listRegions() {
        return residencyService.listRegions();
    }

    @PostMapping("/migrations")
    public MigrationResponse migrate(@Valid @RequestBody MigrationRequest request) {
        return residencyService.migrate(request);
    }

    @GetMapping("/migrations")
    public List<MigrationView> migrationHistory(@RequestParam @NotBlank String subjectKey,
                                                @RequestParam Purpose purpose) {
        return residencyService.migrationHistory(subjectKey, purpose);
    }

    @GetMapping("/grants")
    public List<GrantResidencyView> grantDiagnostics(@RequestParam @NotBlank String subjectKey,
                                                     @RequestParam Purpose purpose) {
        return residencyService.grantDiagnostics(subjectKey, purpose);
    }
}
