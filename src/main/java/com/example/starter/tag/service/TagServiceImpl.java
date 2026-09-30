package com.example.starter.tag.service;

import com.example.starter.tag.domain.Attachment;
import com.example.starter.tag.domain.Handoff;
import com.example.starter.tag.domain.HandoffStatus;
import com.example.starter.tag.domain.IdempotentRequest;
import com.example.starter.tag.domain.Tag;
import com.example.starter.tag.repository.TagRepository;
import com.example.starter.tag.web.AnimalTimelineResponse;
import com.example.starter.tag.web.AttachRequest;
import com.example.starter.tag.web.AttachResponse;
import com.example.starter.tag.web.AttachmentEvent;
import com.example.starter.tag.web.DetachRequest;
import com.example.starter.tag.web.HandoffCancelRequest;
import com.example.starter.tag.web.HandoffConfirmRequest;
import com.example.starter.tag.web.HandoffEvent;
import com.example.starter.tag.web.HandoffInitiateRequest;
import com.example.starter.tag.web.HandoffRejectRequest;
import com.example.starter.tag.web.HandoffResponse;
import com.example.starter.tag.web.TagRegisterRequest;
import com.example.starter.tag.web.TagTimelineResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 标签业务服务：登记、附着（左闭右开区间）、摘除冻结、站点交接与时间线查询。
 *
 * <p>附着与交接发起携带 requestId：同参重放返回首次结果，异参冲突返回 409；
 * 幂等键与业务变更在同一事务内提交，事务失败不消费请求键。
 */
@Service
public class TagServiceImpl implements TagService {

    static final String TYPE_ATTACH = "ATTACH";
    static final String TYPE_HANDOFF_INITIATE = "HANDOFF_INITIATE";

    private final TagRepository repository;
    private final Clock clock;

    public TagServiceImpl(TagRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void registerTag(TagRegisterRequest request) {
        long siteId = requireSite(request.getSiteCode());
        if (repository.findTagByCode(request.getTagCode()).isPresent()) {
            throw new ConflictException("tag already registered: " + request.getTagCode());
        }
        repository.insertTag(request.getTagCode(), siteId, clock.instant());
    }

    @Override
    @Transactional
    public AttachResponse attach(AttachRequest request) {
        Tag tag = requireTag(request.getTagCode());
        long animalId = requireAnimal(request.getAnimalCode());
        long siteId = requireSite(request.getSiteCode());
        Instant attachAt = request.getAttachAt();

        String hash = attachHash(request);
        IdempotentState existing = consumeIdempotencyKey(request.getRequestId(), TYPE_ATTACH, hash);
        if (existing != null) {
            Attachment first = repository.getAttachmentById(existing.resultId());
            return toAttachResponse(first, request.getTagCode(), true);
        }

        // 先锁标签再锁动物，全部附着/摘除/确认事务采用相同加锁顺序以避免死锁。
        repository.lockTag(tag.getId());
        repository.lockAnimal(animalId);

        Tag current = repository.findTagById(tag.getId());
        if (current.getHolderSiteId() == null || current.getHolderSiteId() != siteId) {
            throw new ConflictException(
                    "tag is not held by site " + request.getSiteCode());
        }
        if (attachAt.isBefore(current.getRegisteredAt())) {
            throw new UnprocessableException("attach time is before tag registration time");
        }
        if (repository.findPendingHandoffByTag(tag.getId()).isPresent()) {
            throw new ConflictException(
                    "tag is in a pending handoff and cannot be attached before confirmation");
        }
        // 标签仍佩戴（detach_at 为 NULL 的区间覆盖该时刻）时，附着即区间交叉，统一 422。
        Instant latestTagAttachAt = repository.findLatestAttachAtByTag(tag.getId());
        if (latestTagAttachAt != null && attachAt.isBefore(latestTagAttachAt)) {
            throw new UnprocessableException("attach time must not go backwards");
        }
        if (repository.tagOccupiedAt(tag.getId(), attachAt)) {
            throw new UnprocessableException(
                    "attachment interval overlaps an existing interval for tag "
                            + request.getTagCode());
        }
        if (repository.animalOccupiedAt(animalId, attachAt)) {
            throw new UnprocessableException(
                    "animal already wears an effective tag at the given time");
        }

        long attachmentId;
        try {
            attachmentId = repository.insertAttachment(animalId, tag.getId(), siteId, attachAt);
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("concurrent attachment already exists", ex);
        }
        repository.updateIdempotentResult(request.getRequestId(), TYPE_ATTACH, attachmentId);
        return toAttachResponse(repository.getAttachmentById(attachmentId),
                request.getTagCode(), false);
    }

    @Override
    @Transactional
    public void detach(DetachRequest request) {
        Tag tag = requireTag(request.getTagCode());
        long siteId = requireSite(request.getSiteCode());

        repository.lockTag(tag.getId());
        Tag current = repository.findTagById(tag.getId());
        if (current.getHolderSiteId() == null || current.getHolderSiteId() != siteId) {
            throw new ConflictException(
                    "tag is not held by site " + request.getSiteCode());
        }

        Attachment open = repository.findOpenAttachmentByTag(tag.getId())
                .orElseThrow(() -> new ConflictException(
                        "tag has no active attachment to detach"));
        int updated = repository.closeAttachment(open.getId(), siteId, request.getDetachAt());
        if (updated == 0) {
            throw new UnprocessableException(
                    "detach time must be later than the attachment start time");
        }
    }

    @Override
    @Transactional
    public HandoffResponse initiateHandoff(HandoffInitiateRequest request) {
        Tag tag = requireTag(request.getTagCode());
        long fromSiteId = requireSite(request.getFromSiteCode());
        long toSiteId = requireSite(request.getToSiteCode());
        if (fromSiteId == toSiteId) {
            throw new UnprocessableException("handoff target site must differ from holder site");
        }

        String hash = handoffHash(request);
        IdempotentState existing = consumeIdempotencyKey(
                request.getRequestId(), TYPE_HANDOFF_INITIATE, hash);
        if (existing != null) {
            return toHandoffResponse(repository.getHandoffById(existing.resultId()), true);
        }

        repository.lockTag(tag.getId());
        Tag current = repository.findTagById(tag.getId());
        if (current.getHolderSiteId() == null || current.getHolderSiteId() != fromSiteId) {
            throw new ConflictException(
                    "handoff can only be initiated by the current holder site");
        }
        // 允许先发起交接、再摘除；确认时仍要求标签已摘除。
        if (repository.findPendingHandoffByTag(tag.getId()).isPresent()) {
            throw new ConflictException("a pending handoff already exists for this tag");
        }

        long handoffId = repository.insertHandoff(newHandoffCode(), tag.getId(), fromSiteId,
                toSiteId, request.getInitiateAt());
        repository.updateIdempotentResult(request.getRequestId(), TYPE_HANDOFF_INITIATE,
                handoffId);
        return toHandoffResponse(repository.getHandoffById(handoffId), false);
    }

    @Override
    @Transactional
    public HandoffResponse confirmHandoff(HandoffConfirmRequest request) {
        Handoff handoff = requireHandoff(request.getHandoffCode());
        long siteId = requireSite(request.getSiteCode());

        repository.lockTag(handoff.getTagId());
        Handoff current = repository.getHandoffById(handoff.getId());
        if (current.getStatus() != HandoffStatus.PENDING) {
            throw new ConflictException(
                    "handoff is not pending: " + current.getStatus());
        }
        if (current.getToSiteId() != siteId) {
            throw new UnprocessableException(
                    "handoff can only be confirmed by the target site");
        }
        if (request.getConfirmAt().isBefore(current.getInitiatedAt())) {
            throw new UnprocessableException("confirm time must not be before initiate time");
        }

        Tag tag = repository.findTagById(current.getTagId());
        if (tag.getHolderSiteId() == null
                || tag.getHolderSiteId() != current.getFromSiteId()) {
            throw new ConflictException("tag holder changed before confirmation");
        }
        // 与摘除事务在标签行锁上排队：摘除先提交则确认成功；确认先到则 409，
        // 交接单保持 PENDING、持有权不转移，不会留下半个所有权转移。
        if (repository.findOpenAttachmentByTag(current.getTagId()).isPresent()) {
            throw new ConflictException("tag must be detached before handoff confirmation");
        }

        int holderUpdated = repository.updateTagHolder(current.getTagId(),
                current.getFromSiteId(), current.getToSiteId());
        if (holderUpdated == 0) {
            throw new ConflictException("concurrent holder change detected");
        }
        int statusUpdated = repository.updateHandoffStatus(current.getId(),
                HandoffStatus.PENDING, HandoffStatus.CONFIRMED, request.getConfirmAt(), null,
                null);
        if (statusUpdated == 0) {
            throw new ConflictException("handoff was closed concurrently");
        }
        return toHandoffResponse(repository.getHandoffById(current.getId()), false);
    }

    @Override
    @Transactional
    public HandoffResponse rejectHandoff(HandoffRejectRequest request) {
        Handoff handoff = requireHandoff(request.getHandoffCode());
        long siteId = requireSite(request.getSiteCode());
        if (handoff.getToSiteId() != siteId) {
            throw new UnprocessableException(
                    "handoff can only be rejected by the target site");
        }
        if (handoff.getStatus() != HandoffStatus.PENDING) {
            throw new ConflictException("handoff is not pending: " + handoff.getStatus());
        }
        if (request.getAt().isBefore(handoff.getInitiatedAt())) {
            throw new UnprocessableException("reject time must not be before initiate time");
        }
        int updated = repository.updateHandoffStatus(handoff.getId(), HandoffStatus.PENDING,
                HandoffStatus.REJECTED, null, request.getAt(), request.getReason());
        if (updated == 0) {
            throw new ConflictException("handoff was closed concurrently");
        }
        return toHandoffResponse(repository.getHandoffById(handoff.getId()), false);
    }

    @Override
    @Transactional
    public HandoffResponse cancelHandoff(HandoffCancelRequest request) {
        Handoff handoff = requireHandoff(request.getHandoffCode());
        long siteId = requireSite(request.getSiteCode());
        if (handoff.getFromSiteId() != siteId) {
            throw new UnprocessableException(
                    "handoff can only be cancelled by the initiating site");
        }
        if (handoff.getStatus() == HandoffStatus.CONFIRMED) {
            throw new ConflictException("confirmed handoff cannot be cancelled");
        }
        if (handoff.getStatus() != HandoffStatus.PENDING) {
            throw new ConflictException("handoff is not pending: " + handoff.getStatus());
        }
        if (request.getAt().isBefore(handoff.getInitiatedAt())) {
            throw new UnprocessableException("cancel time must not be before initiate time");
        }
        int updated = repository.updateHandoffStatus(handoff.getId(), HandoffStatus.PENDING,
                HandoffStatus.CANCELLED, null, request.getAt(), null);
        if (updated == 0) {
            throw new ConflictException("handoff was closed concurrently");
        }
        return toHandoffResponse(repository.getHandoffById(handoff.getId()), false);
    }

    @Override
    @Transactional(readOnly = true)
    public TagTimelineResponse tagTimeline(String tagCode) {
        Tag tag = requireTag(tagCode);
        TagTimelineResponse response = new TagTimelineResponse();
        response.setTagCode(tag.getTagCode());
        response.setHolderSiteCode(repository.findSiteCodeById(tag.getHolderSiteId()));
        response.setAttachments(repository.findAttachmentsByTag(tag.getId()).stream()
                .map(this::toAttachmentEvent)
                .toList());
        response.setHandoffs(repository.findHandoffsByTag(tag.getId()).stream()
                .map(this::toHandoffEvent)
                .toList());
        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public AnimalTimelineResponse animalTimeline(String animalCode) {
        long animalId = requireAnimal(animalCode);
        AnimalTimelineResponse response = new AnimalTimelineResponse();
        response.setAnimalCode(animalCode);
        response.setAttachments(repository.findAttachmentsByAnimal(animalId).stream()
                .map(this::toAttachmentEvent)
                .toList());
        return response;
    }

    private record IdempotentState(long resultId) {
    }

    /**
     * 占用幂等键：已存在且同参返回首次结果标识；异参抛 409；不存在则插入占位行。
     * 占位与业务写入同事务，失败整体回滚即不消费请求键。
     */
    private IdempotentState consumeIdempotencyKey(String requestId, String type, String hash) {
        IdempotentRequest existing =
                repository.findIdempotentRequest(requestId, type).orElse(null);
        if (existing != null) {
            if (!existing.getRequestHash().equals(hash)) {
                throw new ConflictException(
                        "requestId was already used with different parameters");
            }
            return new IdempotentState(existing.getResultId());
        }
        try {
            repository.insertIdempotentRequest(requestId, type, hash);
        } catch (DuplicateKeyException ex) {
            IdempotentRequest concurrent =
                    repository.findIdempotentRequest(requestId, type)
                            .orElseThrow(() -> new ConflictException(
                                    "concurrent idempotent request conflict", ex));
            if (!concurrent.getRequestHash().equals(hash)) {
                throw new ConflictException(
                        "requestId was already used with different parameters");
            }
            return new IdempotentState(concurrent.getResultId());
        }
        return null;
    }

    private Tag requireTag(String tagCode) {
        Tag tag = repository.getTagByCode(tagCode);
        if (tag == null) {
            throw new UnprocessableException("unknown tag: " + tagCode);
        }
        return tag;
    }

    private Handoff requireHandoff(String handoffCode) {
        Handoff handoff = repository.getHandoffByCode(handoffCode);
        if (handoff == null) {
            throw new UnprocessableException("unknown handoff: " + handoffCode);
        }
        return handoff;
    }

    private long requireSite(String siteCode) {
        long siteId = repository.requireSiteId(siteCode);
        if (siteId == 0L) {
            throw new UnprocessableException("unknown site: " + siteCode);
        }
        return siteId;
    }

    private long requireAnimal(String animalCode) {
        long animalId = repository.requireAnimalId(animalCode);
        if (animalId == 0L) {
            throw new UnprocessableException("unknown animal: " + animalCode);
        }
        return animalId;
    }

    private String newHandoffCode() {
        return "H-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
    }

    private AttachResponse toAttachResponse(Attachment attachment, String tagCode,
                                            boolean replay) {
        AttachResponse response = new AttachResponse();
        response.setAttachmentId(attachment.getId());
        response.setTagCode(tagCode);
        response.setAnimalCode(repository.findAnimalCodeById(attachment.getAnimalId()));
        response.setSiteCode(repository.findSiteCodeById(attachment.getSiteId()));
        response.setAttachAt(attachment.getAttachAt());
        response.setReplay(replay);
        return response;
    }

    private AttachmentEvent toAttachmentEvent(Attachment attachment) {
        AttachmentEvent event = new AttachmentEvent();
        event.setTagCode(repository.findTagById(attachment.getTagId()).getTagCode());
        event.setAnimalCode(repository.findAnimalCodeById(attachment.getAnimalId()));
        event.setSiteCode(repository.findSiteCodeById(attachment.getSiteId()));
        event.setAttachAt(attachment.getAttachAt());
        event.setDetachAt(attachment.getDetachAt());
        if (attachment.getDetachSiteId() != null) {
            event.setDetachSiteCode(repository.findSiteCodeById(attachment.getDetachSiteId()));
        }
        return event;
    }

    private HandoffResponse toHandoffResponse(Handoff handoff, boolean replay) {
        HandoffResponse response = new HandoffResponse();
        response.setHandoffCode(handoff.getHandoffCode());
        response.setTagCode(repository.findTagById(handoff.getTagId()).getTagCode());
        response.setFromSiteCode(repository.findSiteCodeById(handoff.getFromSiteId()));
        response.setToSiteCode(repository.findSiteCodeById(handoff.getToSiteId()));
        response.setStatus(handoff.getStatus());
        response.setInitiatedAt(handoff.getInitiatedAt());
        response.setConfirmedAt(handoff.getConfirmedAt());
        response.setRejectReason(handoff.getRejectReason());
        response.setReplay(replay);
        return response;
    }

    private HandoffEvent toHandoffEvent(Handoff handoff) {
        HandoffEvent event = new HandoffEvent();
        event.setHandoffCode(handoff.getHandoffCode());
        event.setFromSiteCode(repository.findSiteCodeById(handoff.getFromSiteId()));
        event.setToSiteCode(repository.findSiteCodeById(handoff.getToSiteId()));
        event.setStatus(handoff.getStatus());
        event.setInitiatedAt(handoff.getInitiatedAt());
        event.setConfirmedAt(handoff.getConfirmedAt());
        event.setClosedAt(handoff.getClosedAt());
        event.setRejectReason(handoff.getRejectReason());
        return event;
    }

    private static String attachHash(AttachRequest request) {
        return sha256(String.join("|",
                request.getTagCode(), request.getAnimalCode(), request.getSiteCode(),
                request.getAttachAt().toString()));
    }

    private static String handoffHash(HandoffInitiateRequest request) {
        return sha256(String.join("|",
                request.getTagCode(), request.getFromSiteCode(), request.getToSiteCode(),
                request.getInitiateAt().toString()));
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
