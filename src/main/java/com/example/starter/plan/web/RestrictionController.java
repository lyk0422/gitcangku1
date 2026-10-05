package com.example.starter.plan.web;

import com.example.starter.plan.service.RestrictionService;
import com.example.starter.plan.web.dto.EffectiveSpeedView;
import com.example.starter.plan.web.dto.RegisterRestrictionRequest;
import com.example.starter.plan.web.dto.RestrictionActionRequest;
import com.example.starter.plan.web.dto.RestrictionResponse;
import com.example.starter.plan.web.dto.ReviseRestrictionRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
 * 气象限速令 API：登记、修订、撤销、明细、版本历史、按区段列表与生效限速诊断。
 * 所有查询为只读，不改变任何状态。
 */
@Validated
@RestController
@RequestMapping("/api/v1/speed-restrictions")
public class RestrictionController {

    private final RestrictionService service;

    public RestrictionController(RestrictionService service) {
        this.service = service;
    }

    /**
     * 登记限速令（版本 1）。区段未注册、时段非法或速度越界返回 422。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RestrictionResponse register(@Valid @RequestBody RegisterRestrictionRequest request) {
        return service.register(request);
    }

    /**
     * 修订限速令：产生版本加一的新版本，旧版本置为 SUPERSEDED。
     */
    @PutMapping("/{restrictionKey}")
    public RestrictionResponse revise(@PathVariable String restrictionKey,
                                      @Valid @RequestBody ReviseRestrictionRequest request) {
        return service.revise(restrictionKey, request);
    }

    /**
     * 撤销限速令：最新版本置为 REVOKED，历史版本保留。
     */
    @PostMapping("/{restrictionKey}/revoke")
    public RestrictionResponse revoke(@PathVariable String restrictionKey,
                                      @Valid @RequestBody RestrictionActionRequest request) {
        return service.revoke(restrictionKey, request.requestKey(), request.operator());
    }

    /**
     * 限速令最新版本明细。
     */
    @GetMapping("/{restrictionKey}")
    public RestrictionResponse getRestriction(@PathVariable String restrictionKey) {
        return service.getRestriction(restrictionKey);
    }

    /**
     * 限速令全部版本历史（含 SUPERSEDED / REVOKED），按版本升序。
     */
    @GetMapping("/{restrictionKey}/history")
    public List<RestrictionResponse> getHistory(@PathVariable String restrictionKey) {
        return service.getHistory(restrictionKey);
    }

    /**
     * 按区段查询全部限速令版本（历史证据），按业务键与版本升序。
     */
    @GetMapping
    public List<RestrictionResponse> listBySection(@RequestParam @NotBlank String sectionId) {
        return service.listBySection(sectionId);
    }

    /**
     * 诊断：区段在指定时刻的生效限速（多条叠加取最低值；无生效限速时 effectiveSpeedKmh 为 null）。
     */
    @GetMapping("/effective")
    public EffectiveSpeedView getEffectiveSpeed(
            @RequestParam @NotBlank String sectionId,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant at) {
        return service.getEffectiveSpeed(sectionId, at);
    }
}
