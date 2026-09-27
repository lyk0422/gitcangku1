package com.example.starter.consent;

import java.time.Instant;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.consent.dto.BatchQueryRequest;
import com.example.starter.consent.dto.BatchQueryResponse;
import com.example.starter.consent.dto.EpochDiagnosticResponse;
import com.example.starter.consent.dto.MigrateRequest;
import com.example.starter.consent.dto.MigrateResponse;
import com.example.starter.consent.dto.RegionBindRequest;
import com.example.starter.consent.dto.RegionBindResponse;
import com.example.starter.consent.dto.RegionRegisterRequest;
import com.example.starter.consent.dto.RegionResponse;
import com.example.starter.consent.dto.SnapshotDetailResponse;
import com.example.starter.consent.dto.SnapshotSummaryResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * 授权数据驻留 API：区域登记与绑定、驻留迁移、跨对象路由查询及只读的快照明细、历史与诊断。
 */
@Validated
@RestController
@RequestMapping("/api/v1/residency")
public class ResidencyController {

    private final ResidencyService residencyService;

    public ResidencyController(ResidencyService residencyService) {
        this.residencyService = residencyService;
    }

    /**
     * 区域登记：区域代码必须预先登记后才能用于绑定与写入路由。
     */
    @PostMapping("/regions")
    public RegionResponse registerRegion(@Valid @RequestBody RegionRegisterRequest request) {
        return residencyService.registerRegion(request);
    }

    /**
     * 已登记区域清单（只读）。
     */
    @GetMapping("/regions")
    public List<RegionResponse> listRegions() {
        return residencyService.listRegions();
    }

    /**
     * 区域绑定：为指定授权代次登记驻留区域，绑定后不可直接修改。
     */
    @PostMapping("/bindings")
    public RegionBindResponse bindRegion(@Valid @RequestBody RegionBindRequest request) {
        return residencyService.bindRegion(request);
    }

    /**
     * 驻留迁移：新代次 + 原子复制 + 源代次撤回，任一对象不合格整次 422。
     */
    @PostMapping("/migrations")
    public MigrateResponse migrate(@Valid @RequestBody MigrateRequest request) {
        return residencyService.migrate(request);
    }

    /**
     * 跨对象路由查询：成功返回冻结的合规快照。
     */
    @PostMapping("/queries")
    public BatchQueryResponse query(@Valid @RequestBody BatchQueryRequest request) {
        return residencyService.query(request);
    }

    /**
     * 快照明细（只读）。
     */
    @GetMapping("/snapshots/{snapshotId}")
    public SnapshotDetailResponse getSnapshot(@PathVariable @NotBlank String snapshotId) {
        return residencyService.getSnapshot(snapshotId);
    }

    /**
     * 快照历史（只读）：UTC 时间区间左闭右开 [from, to)。
     */
    @GetMapping("/snapshots")
    public List<SnapshotSummaryResponse> listSnapshots(
            @RequestParam @NotBlank String subjectKey,
            @RequestParam Purpose purpose,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return residencyService.listSnapshots(subjectKey, purpose, from, to);
    }

    /**
     * 代次驻留诊断（只读）。
     */
    @GetMapping("/diagnostics/epochs")
    public List<EpochDiagnosticResponse> diagnoseEpochs(
            @RequestParam @NotBlank String subjectKey,
            @RequestParam Purpose purpose) {
        return residencyService.diagnoseEpochs(subjectKey, purpose);
    }
}
