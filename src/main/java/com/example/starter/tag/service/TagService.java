package com.example.starter.tag.service;

import com.example.starter.tag.web.AnimalTimelineResponse;
import com.example.starter.tag.web.AttachRequest;
import com.example.starter.tag.web.AttachResponse;
import com.example.starter.tag.web.DetachRequest;
import com.example.starter.tag.web.HandoffCancelRequest;
import com.example.starter.tag.web.HandoffConfirmRequest;
import com.example.starter.tag.web.HandoffInitiateRequest;
import com.example.starter.tag.web.HandoffRejectRequest;
import com.example.starter.tag.web.HandoffResponse;
import com.example.starter.tag.web.TagRegisterRequest;
import com.example.starter.tag.web.TagTimelineResponse;

/**
 * 标签登记、附着、摘除与站点间交接的业务入口。
 */
public interface TagService {

    void registerTag(TagRegisterRequest request);

    AttachResponse attach(AttachRequest request);

    void detach(DetachRequest request);

    HandoffResponse initiateHandoff(HandoffInitiateRequest request);

    HandoffResponse confirmHandoff(HandoffConfirmRequest request);

    HandoffResponse rejectHandoff(HandoffRejectRequest request);

    HandoffResponse cancelHandoff(HandoffCancelRequest request);

    TagTimelineResponse tagTimeline(String tagCode);

    AnimalTimelineResponse animalTimeline(String animalCode);
}
