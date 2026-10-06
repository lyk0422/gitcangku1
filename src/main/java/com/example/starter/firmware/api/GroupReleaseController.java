package com.example.starter.firmware.api;

import com.example.starter.firmware.service.GroupReleaseService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 固定设备组预备屏障：新建组、成员准备回执、激活、取消与查询。
 */
@RestController
@RequestMapping("/api/group-releases")
public class GroupReleaseController {

    private final GroupReleaseService groupReleaseService;

    public GroupReleaseController(GroupReleaseService groupReleaseService) {
        this.groupReleaseService = groupReleaseService;
    }

    @PostMapping
    public GroupReleaseView create(@Valid @RequestBody CreateGroupReleaseRequest request) {
        return groupReleaseService.create(request);
    }

    @GetMapping("/{groupId}")
    public GroupReleaseView get(@PathVariable long groupId) {
        return groupReleaseService.get(groupId);
    }

    @PostMapping("/{groupId}/members/{deviceId}/prepare")
    public GroupReleaseView prepare(@PathVariable long groupId, @PathVariable String deviceId,
                                    @Valid @RequestBody GroupPrepareRequest request) {
        return groupReleaseService.prepare(groupId, deviceId, request);
    }

    @PostMapping("/{groupId}/activate")
    public GroupReleaseView activate(@PathVariable long groupId,
                                     @Valid @RequestBody GroupActivateRequest request) {
        return groupReleaseService.activate(groupId, request);
    }

    @PostMapping("/{groupId}/cancel")
    public GroupReleaseView cancel(@PathVariable long groupId,
                                   @Valid @RequestBody GroupCancelRequest request) {
        return groupReleaseService.cancel(groupId, request.requestId(), request.operator());
    }
}
