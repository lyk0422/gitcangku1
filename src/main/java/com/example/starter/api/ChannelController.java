package com.example.starter.api;

import com.example.starter.api.dto.Requests;
import com.example.starter.api.dto.Responses;
import com.example.starter.service.ChannelService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 频道接口。
 */
@RestController
@RequestMapping("/channels")
public class ChannelController {

    private final ChannelService channelService;

    public ChannelController(ChannelService channelService) {
        this.channelService = channelService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Responses.ChannelView create(@RequestBody Requests.CreateChannel request) {
        return ViewMapper.toView(channelService.create(request.id(), request.fallbackAssetId()));
    }

    @GetMapping("/{id}")
    public Responses.ChannelView get(@PathVariable String id) {
        return ViewMapper.toView(channelService.get(id));
    }
}
