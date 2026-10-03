package com.example.starter.service;

import java.time.Clock;
import java.time.Instant;

import com.example.starter.domain.AttachmentRow;
import com.example.starter.domain.TagRow;
import com.example.starter.domain.TagStatus;
import com.example.starter.repo.AnimalRepository;
import com.example.starter.repo.AttachmentRepository;
import com.example.starter.repo.TagRepository;
import com.example.starter.repo.TransferRepository;
import com.example.starter.web.dto.ApiDtos.AttachRequest;
import com.example.starter.web.dto.ApiDtos.AttachmentResponse;
import com.example.starter.web.dto.ApiDtos.DetachRequest;
import com.example.starter.web.dto.ApiDtos.RegisterTagRequest;
import com.example.starter.web.dto.ApiDtos.TagResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 标签登记、附着、摘除。所有写操作先取标签行锁，保证并发下按提交顺序判定。
 */
@Service
public class TagService {

    private static final String OP_ATTACH = "ATTACH";
    private static final String OP_DETACH = "DETACH";
    private static final String OP_REGISTER = "REGISTER_TAG";

    private final TagRepository tagRepository;
    private final AttachmentRepository attachmentRepository;
    private final AnimalRepository animalRepository;
    private final TransferRepository transferRepository;
    private final IdempotencyExecutor idempotency;
    private final Clock clock;

    public TagService(TagRepository tagRepository, AttachmentRepository attachmentRepository,
            AnimalRepository animalRepository, TransferRepository transferRepository,
            IdempotencyExecutor idempotency, Clock clock) {
        this.tagRepository = tagRepository;
        this.attachmentRepository = attachmentRepository;
        this.animalRepository = animalRepository;
        this.transferRepository = transferRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    @Transactional
    public TagResponse registerTag(RegisterTagRequest request) {
        if (!tagRepository.stationExists(request.stationCode())) {
            throw BusinessException.unprocessable("STATION_NOT_FOUND",
                    "站点不存在: " + request.stationCode(), request.tagCode(), OP_REGISTER);
        }
        if (tagRepository.findByCode(request.tagCode()).isPresent()) {
            throw BusinessException.conflict("TAG_ALREADY_EXISTS",
                    "标签已登记: " + request.tagCode(), request.tagCode(), OP_REGISTER);
        }
        Instant now = Instant.now(clock);
        tagRepository.insert(request.tagCode(), request.stationCode(), now);
        return new TagResponse(request.tagCode(), request.stationCode(), TagStatus.DETACHED.name(), now);
    }

    @Transactional(readOnly = true)
    public TagResponse getTag(String tagCode) {
        return toResponse(requireTag(tagCode));
    }

    /**
     * 附着：标签须已摘除、由当前持有站点操作、无待确认交接；
     * 动物与标签在该时刻之后均不得存在交叉区间（左闭右开）。
     */
    @Transactional
    public AttachmentResponse attach(AttachRequest request) {
        return idempotency.execute(OP_ATTACH, request.requestId(), request, AttachmentResponse.class,
                () -> doAttach(request));
    }

    private AttachmentResponse doAttach(AttachRequest request) {
        TagRow tag = requireTagForUpdate(request.tagCode());
        if (tag.status() == TagStatus.ATTACHED) {
            throw BusinessException.conflict("TAG_STILL_ATTACHED",
                    "标签未摘除，不能再次附着: " + request.tagCode(), request.tagCode(), OP_ATTACH)
                    .withAnimal(request.animalCode());
        }
        if (!tag.holderStationCode().equals(request.stationCode())) {
            throw BusinessException.conflict("NOT_HOLDER_STATION",
                    "站点 " + request.stationCode() + " 不是标签当前持有站点", request.tagCode(), OP_ATTACH)
                    .withAnimal(request.animalCode());
        }
        if (transferRepository.findPendingByTag(request.tagCode()).isPresent()) {
            throw BusinessException.conflict("TRANSFER_PENDING",
                    "标签存在待确认交接，不能附着: " + request.tagCode(), request.tagCode(), OP_ATTACH)
                    .withAnimal(request.animalCode());
        }
        if (attachmentRepository.existsTagOverlap(request.tagCode(), request.startAt())) {
            throw BusinessException.unprocessable("TAG_INTERVAL_OVERLAP",
                    "附着时刻早于或等于标签既有区间，时间倒退或交叉: " + request.startAt(),
                    request.tagCode(), OP_ATTACH).withAnimal(request.animalCode());
        }
        if (attachmentRepository.existsAnimalOverlap(request.animalCode(), request.startAt())) {
            throw BusinessException.unprocessable("ANIMAL_INTERVAL_OVERLAP",
                    "动物 " + request.animalCode() + " 在该时刻已有有效标签，区间交叉",
                    request.tagCode(), OP_ATTACH).withAnimal(request.animalCode());
        }
        animalRepository.insertIfAbsent(request.animalCode(), Instant.now(clock));
        AttachmentRow row = attachmentRepository.insert(
                request.tagCode(), request.animalCode(), request.stationCode(), request.startAt());
        tagRepository.updateStatus(request.tagCode(), TagStatus.ATTACHED);
        return toResponse(row);
    }

    /** 摘除：冻结当前关联（写 end_at），不覆盖历史；endAt 不得早于 startAt。 */
    @Transactional
    public AttachmentResponse detach(DetachRequest request) {
        TagRow tag = requireTagForUpdate(request.tagCode());
        AttachmentRow open = attachmentRepository.findOpenByTag(request.tagCode())
                .orElseThrow(() -> BusinessException.conflict("TAG_NOT_ATTACHED",
                        "标签当前未附着: " + request.tagCode(), request.tagCode(), OP_DETACH));
        if (!open.stationCode().equals(request.stationCode())) {
            throw BusinessException.conflict("NOT_HOLDER_STATION",
                    "站点 " + request.stationCode() + " 不是附着站点，不能摘除", request.tagCode(), OP_DETACH);
        }
        if (request.endAt().isBefore(open.startAt())) {
            throw BusinessException.unprocessable("TIME_REGRESSION",
                    "摘除时刻早于附着时刻: " + request.endAt(), request.tagCode(), OP_DETACH);
        }
        attachmentRepository.close(open.id(), request.endAt());
        tagRepository.updateStatus(request.tagCode(), TagStatus.DETACHED);
        return toResponse(new AttachmentRow(open.id(), open.tagCode(), open.animalCode(),
                open.stationCode(), open.startAt(), request.endAt()));
    }

    TagRow requireTag(String tagCode) {
        return tagRepository.findByCode(tagCode)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "TAG_NOT_FOUND",
                        "标签不存在: " + tagCode, tagCode, null, null));
    }

    TagRow requireTagForUpdate(String tagCode) {
        return tagRepository.findByCodeForUpdate(tagCode)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "TAG_NOT_FOUND",
                        "标签不存在: " + tagCode, tagCode, null, null));
    }

    static TagResponse toResponse(TagRow row) {
        return new TagResponse(row.tagCode(), row.holderStationCode(), row.status().name(), row.registeredAt());
    }

    static AttachmentResponse toResponse(AttachmentRow row) {
        return new AttachmentResponse(row.id(), row.tagCode(), row.animalCode(), row.stationCode(),
                row.startAt(), row.endAt());
    }
}