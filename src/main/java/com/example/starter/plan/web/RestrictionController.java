package com.example.starter.plan.web;

import com.example.starter.plan.service.RestrictionService;
import com.example.starter.plan.web.dto.EffectiveLimitView;
import com.example.starter.plan.web.dto.RestrictionActionRequest;
import com.example.starter.plan.web.dto.RestrictionRegisterRequest;
import com.example.starter.plan.web.dto.RestrictionResponse;
import com.example.starter.plan.web.dto.RestrictionReviseRequest;
import com.example.starter.plan.web.dto.SectionProfileRequest;
import com.example.starter.plan.web.dto.SectionProfileResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 气象限速 API：区段档案登记、限速令登记/撤销/修订、限速令明细与历史、
 * 区段某时刻生效限速诊断。查询均为只读，不改变状态。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class RestrictionController {

    private final RestrictionService service;

    public RestrictionController(RestrictionService service) {
        this.service = service;
    }

    /**
     * 登记线路区段档案（设计速度与最小间隔）。
     */
    @PostMapping("/sections")
    @ResponseStatus(HttpStatus.CREATED)
    public SectionProfileResponse registerSection(@Valid @RequestBody SectionProfileRequest request) {
        return service.registerSection(request);
    }

    /**
     * 查询区段档案。
     */
    @GetMapping("/sections/{sectionId}")
    public SectionProfileResponse getSection(@PathVariable String sectionId) {
        return service.getSection(sectionId);
    }

    /**
     * 诊断：区段在某 UTC 时刻的生效限速（多条叠加以最低速度为准，无生效限速时 effectiveSpeedKmh 为 null）。
     */
    @GetMapping("/sections/{sectionId}/speed-limit")
    public EffectiveLimitView effectiveLimit(
            @PathVariable String sectionId,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant at) {
        return service.effectiveLimit(sectionId, at);
    }

    /**
     * 登记限速令（版本 1，ACTIVE）。
     */
    @PostMapping("/restrictions")
    @ResponseStatus(HttpStatus.CREATED)
    public RestrictionResponse register(@Valid @RequestBody RestrictionRegisterRequest request) {
        return service.register(request);
    }

    /**
     * 限速令历史列表；sectionId 为空时返回全部。
     */
    @GetMapping("/restrictions")
    public List<RestrictionResponse> list(@RequestParam(required = false) String sectionId) {
        return service.listRestrictions(sectionId);
    }

    /**
     * 限速令明细。
     */
    @GetMapping("/restrictions/{restrictionKey}")
    public RestrictionResponse get(@PathVariable String restrictionKey) {
        return service.getRestriction(restrictionKey);
    }

    /**
     * 撤销限速令：不再影响后续发布或改签，已生成重排记录不改写。
     */
    @PostMapping("/restrictions/{restrictionKey}/revoke")
    public RestrictionResponse revoke(@PathVariable String restrictionKey,
                                      @Valid @RequestBody RestrictionActionRequest request) {
        return service.revoke(restrictionKey, request);
    }

    /**
     * 修订限速令：调整时段与最高速度，版本加一。
     */
    @PutMapping("/restrictions/{restrictionKey}")
    public RestrictionResponse revise(@PathVariable String restrictionKey,
                                      @Valid @RequestBody RestrictionReviseRequest request) {
        return service.revise(restrictionKey, request);
    }
}
