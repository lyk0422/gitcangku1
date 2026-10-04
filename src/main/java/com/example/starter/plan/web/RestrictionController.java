package com.example.starter.plan.web;

import com.example.starter.plan.service.RestrictionService;
import com.example.starter.plan.web.dto.AmendRestrictionRequest;
import com.example.starter.plan.web.dto.EffectiveSpeedView;
import com.example.starter.plan.web.dto.RegisterRestrictionRequest;
import com.example.starter.plan.web.dto.RestrictionResponse;
import com.example.starter.plan.web.dto.RevokeRestrictionRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
 * 铁路气象限速令 API：登记、修订、撤销、明细/历史查询与生效速度诊断。
 * 查询类接口只读，不改变任何状态。
 */
@Validated
@RestController
@RequestMapping("/api/v1/restrictions")
public class RestrictionController {

    private final RestrictionService service;

    public RestrictionController(RestrictionService service) {
        this.service = service;
    }

    /**
     * 登记限速令（按区段、UTC 左闭右开时段与最高速度）。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RestrictionResponse register(@Valid @RequestBody RegisterRestrictionRequest request) {
        return service.register(request);
    }

    /**
     * 修订限速令（expectedVersion 乐观校验，成功版本加一，不改写历史重排记录）。
     */
    @PutMapping("/{restrictionKey}")
    public RestrictionResponse amend(@PathVariable String restrictionKey,
                                     @Valid @RequestBody AmendRestrictionRequest request) {
        return service.amend(restrictionKey, request);
    }

    /**
     * 撤销限速令（不再影响后续发布或改签，历史保留）。
     */
    @PostMapping("/{restrictionKey}/revoke")
    public RestrictionResponse revoke(@PathVariable String restrictionKey,
                                      @Valid @RequestBody RevokeRestrictionRequest request) {
        return service.revoke(restrictionKey, request);
    }

    /**
     * 限速令明细。
     */
    @GetMapping("/{restrictionKey}")
    public RestrictionResponse getRestriction(@PathVariable String restrictionKey) {
        return service.getRestriction(restrictionKey);
    }

    /**
     * 限速令历史清单（含已撤销），可按区段过滤。
     */
    @GetMapping
    public List<RestrictionResponse> listRestrictions(
            @RequestParam Optional<String> sectionId) {
        return service.listRestrictions(sectionId);
    }

    /**
     * 诊断：区段在指定时刻的生效限速（多条叠加以最低速度为准，无则 effectiveSpeedKmh 为 null）。
     */
    @GetMapping("/effective")
    public EffectiveSpeedView getEffectiveSpeed(
            @RequestParam String sectionId,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant at) {
        return service.getEffectiveSpeed(sectionId, at);
    }
}