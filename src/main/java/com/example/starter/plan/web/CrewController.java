package com.example.starter.plan.web;

import com.example.starter.plan.service.CrewService;
import com.example.starter.plan.web.dto.CrewMemberRequest;
import com.example.starter.plan.web.dto.CrewMemberView;
import com.example.starter.plan.web.dto.GrantQualificationRequest;
import com.example.starter.plan.web.dto.LineSectionRequest;
import com.example.starter.plan.web.dto.LineSectionView;
import com.example.starter.plan.web.dto.QualificationView;
import jakarta.validation.Valid;
import java.util.List;
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
 * 乘务员、资格版本与线路区段管理 API。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class CrewController {

    private final CrewService service;

    public CrewController(CrewService service) {
        this.service = service;
    }

    /**
     * 登记乘务员（工号唯一）。
     */
    @PostMapping("/crew-members")
    @ResponseStatus(HttpStatus.CREATED)
    public CrewMemberView registerMember(@Valid @RequestBody CrewMemberRequest request) {
        return service.registerMember(request.memberKey(), request.name());
    }

    /**
     * 列出全部乘务员，按工号升序。
     */
    @GetMapping("/crew-members")
    public List<CrewMemberView> listMembers() {
        return service.listMembers();
    }

    /**
     * 授予乘务资格新版本（同人员同线路上一 ACTIVE 版本自动 SUPERSEDED）。
     */
    @PostMapping("/crew-members/{memberKey}/qualifications")
    @ResponseStatus(HttpStatus.CREATED)
    public QualificationView grantQualification(@PathVariable String memberKey,
                                                @Valid @RequestBody GrantQualificationRequest request) {
        return service.grantQualification(memberKey, request);
    }

    /**
     * 查询人员资格版本；可按 lineId 过滤，按线路与版本号升序。
     */
    @GetMapping("/crew-members/{memberKey}/qualifications")
    public List<QualificationView> listQualifications(@PathVariable String memberKey,
                                                      @RequestParam(required = false) String lineId) {
        return service.listQualifications(memberKey, lineId);
    }

    /**
     * 撤销指定资格版本（仅 ACTIVE 可撤销）；与发布并发时按资格行锁裁决。
     */
    @PostMapping("/crew-members/{memberKey}/qualifications/{lineId}/versions/{version}/revoke")
    public QualificationView revokeQualification(@PathVariable String memberKey,
                                                 @PathVariable String lineId,
                                                 @PathVariable int version) {
        return service.revokeQualification(memberKey, lineId, version);
    }

    /**
     * 登记线路区段（区段 ID 唯一）。
     */
    @PutMapping("/line-sections/{sectionId}")
    public LineSectionView registerSection(@PathVariable String sectionId,
                                           @Valid @RequestBody LineSectionRequest request) {
        return service.registerSection(sectionId, request.lineId(), request.requiredGrade());
    }
}
