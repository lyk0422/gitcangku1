package com.example.starter.api;

import com.example.starter.api.dto.Requests;
import com.example.starter.api.dto.Responses;
import com.example.starter.common.TimeSupport;
import com.example.starter.service.GrantService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 授权接口：创建、查询与撤销。
 */
@RestController
public class GrantController {

    private final GrantService grantService;

    public GrantController(GrantService grantService) {
        this.grantService = grantService;
    }

    @PostMapping("/channels/{channelId}/grants")
    @ResponseStatus(HttpStatus.CREATED)
    public Responses.GrantView create(@PathVariable String channelId,
                                      @RequestBody Requests.CreateGrant request) {
        Instant validFrom = TimeSupport.parse(request.validFrom(), "validFrom");
        Instant validTo = TimeSupport.parse(request.validTo(), "validTo");
        return ViewMapper.toView(
                grantService.create(channelId, request.assetId(), validFrom, validTo));
    }

    @PostMapping("/grants/{grantId}/revoke")
    public Responses.GrantView revoke(@PathVariable String grantId,
                                      @RequestBody Requests.Revoke request) {
        return ViewMapper.toView(grantService.revoke(grantId, request.requestId()));
    }

    @GetMapping("/grants/{grantId}")
    public Responses.GrantView get(@PathVariable String grantId) {
        return ViewMapper.toView(grantService.get(grantId));
    }
}
