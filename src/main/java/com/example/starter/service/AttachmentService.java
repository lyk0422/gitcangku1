package com.example.starter.service;

import com.example.starter.domain.Attachment;
import com.example.starter.repo.AttachmentRepository;
import com.example.starter.repo.TagOwnershipRepository;
import com.example.starter.repo.TransferRepository;
import java.time.Instant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 附着与摘除：一个动物同一时刻最多一个有效标签，一个标签同一时刻最多对应一个动物；
 * 有效期左闭右开，摘除时冻结上一段关联，不覆盖历史。
 */
@Service
public class AttachmentService {

    /**
     * 附着记录视图。
     *
     * @param id 附着记录主键
     * @param tagCode 标签编号
     * @param animalCode 动物编号（合成）
     * @param stationCode 执行附着的站点编号
     * @param attachedAt 附着开始时刻（UTC，左闭）
     * @param detachedAt 摘除时刻（UTC，右开；null 表示仍附着）
     */
    public record AttachmentView(long id, String tagCode, String animalCode, String stationCode,
            Instant attachedAt, Instant detachedAt) {

        static AttachmentView of(Attachment a) {
            return new AttachmentView(a.id(), a.tagCode(), a.animalCode(), a.stationCode(),
                    a.attachedAt(), a.detachedAt());
        }
    }

    private final TagOwnershipRepository ownerships;
    private final AttachmentRepository attachments;
    private final TransferRepository transfers;
    private final Idempotency idempotency;

    public AttachmentService(TagOwnershipRepository ownerships, AttachmentRepository attachments,
            TransferRepository transfers, Idempotency idempotency) {
        this.ownerships = ownerships;
        this.attachments = attachments;
        this.transfers = transfers;
        this.idempotency = idempotency;
    }

    /**
     * 附着：仅当前持有站点可执行；要求标签已摘除、无待确认交接、区间不倒退不交叉。
     * 使用 requestId 幂等：同参重放，异参 409。
     */
    @Transactional
    public AttachmentView attach(String requestId, String tagCode, String animalCode,
            String stationCode, Instant attachedAt) {
        String fingerprint = String.join("|", tagCode, animalCode, stationCode, attachedAt.toString());
        return idempotency.run(requestId, "ATTACH", fingerprint, AttachmentView.class, () -> {
            var ownership = ownerships.findForUpdate(tagCode).orElseThrow(() ->
                    notFound("ATTACH", tagCode, animalCode, stationCode));
            if (!ownership.stationCode().equals(stationCode)) {
                throw BusinessException.conflict("TAG_NOT_HELD_BY_STATION",
                        "标签当前由站点 " + ownership.stationCode() + " 持有")
                        .operation("ATTACH").tagCode(tagCode).animalCode(animalCode)
                        .stationCode(stationCode).build();
            }
            attachments.findOpenByTag(tagCode).ifPresent(open -> {
                throw BusinessException.conflict("TAG_STILL_ATTACHED",
                        "标签尚未摘除，仍附着在动物 " + open.animalCode())
                        .operation("ATTACH").tagCode(tagCode).animalCode(animalCode)
                        .stationCode(stationCode).build();
            });
            transfers.findPendingByTag(tagCode).ifPresent(pending -> {
                throw BusinessException.conflict("TRANSFER_PENDING",
                        "标签存在待确认交接单 " + pending.id() + "，持有权未定")
                        .operation("ATTACH").tagCode(tagCode).animalCode(animalCode)
                        .stationCode(stationCode).build();
            });
            attachments.maxDetachedAtByTag(tagCode).ifPresent(last -> {
                if (attachedAt.isBefore(last)) {
                    throw BusinessException.unprocessable("TAG_INTERVAL_CROSSING",
                            "附着时刻早于标签上一段摘除时刻 " + last + "，区间交叉")
                            .operation("ATTACH").tagCode(tagCode).animalCode(animalCode)
                            .stationCode(stationCode).build();
                }
            });
            attachments.findOpenByAnimal(animalCode).ifPresent(open -> {
                throw BusinessException.conflict("ANIMAL_HAS_ACTIVE_TAG",
                        "动物当前仍附着标签 " + open.tagCode())
                        .operation("ATTACH").tagCode(tagCode).animalCode(animalCode)
                        .stationCode(stationCode).build();
            });
            attachments.maxDetachedAtByAnimal(animalCode).ifPresent(last -> {
                if (attachedAt.isBefore(last)) {
                    throw BusinessException.unprocessable("ANIMAL_INTERVAL_CROSSING",
                            "附着时刻早于动物上一段摘除时刻 " + last + "，区间交叉")
                            .operation("ATTACH").tagCode(tagCode).animalCode(animalCode)
                            .stationCode(stationCode).build();
                }
            });
            long id;
            try {
                id = attachments.insert(tagCode, animalCode, stationCode, attachedAt, requestId);
            } catch (DuplicateKeyException e) {
                throw BusinessException.conflict("ACTIVE_ATTACHMENT_CONFLICT",
                        "并发写入冲突：标签或动物已存在有效附着")
                        .operation("ATTACH").tagCode(tagCode).animalCode(animalCode)
                        .stationCode(stationCode).build();
            }
            return new AttachmentView(id, tagCode, animalCode, stationCode, attachedAt, null);
        });
    }

    /**
     * 摘除：仅当前持有站点可执行；冻结上一段关联的右端点，不覆盖历史。
     */
    @Transactional
    public AttachmentView detach(String tagCode, String stationCode, Instant detachedAt) {
        var ownership = ownerships.findForUpdate(tagCode).orElseThrow(() ->
                notFound("DETACH", tagCode, null, stationCode));
        if (!ownership.stationCode().equals(stationCode)) {
            throw BusinessException.conflict("TAG_NOT_HELD_BY_STATION",
                    "标签当前由站点 " + ownership.stationCode() + " 持有")
                    .operation("DETACH").tagCode(tagCode).stationCode(stationCode).build();
        }
        var open = attachments.findOpenByTag(tagCode).orElseThrow(() ->
                BusinessException.conflict("TAG_NOT_ATTACHED", "标签当前未附着在任何动物上")
                        .operation("DETACH").tagCode(tagCode).stationCode(stationCode).build());
        if (!detachedAt.isAfter(open.attachedAt())) {
            throw BusinessException.unprocessable("DETACH_TIME_REGRESSION",
                    "摘除时刻必须晚于附着时刻 " + open.attachedAt())
                    .operation("DETACH").tagCode(tagCode).animalCode(open.animalCode())
                    .stationCode(stationCode).build();
        }
        attachments.close(open.id(), detachedAt);
        return new AttachmentView(open.id(), open.tagCode(), open.animalCode(), open.stationCode(),
                open.attachedAt(), detachedAt);
    }

    private BusinessException notFound(String operation, String tagCode, String animalCode, String stationCode) {
        return BusinessException.notFound("TAG_NOT_FOUND", "标签未登记: " + tagCode)
                .operation(operation).tagCode(tagCode).animalCode(animalCode)
                .stationCode(stationCode).build();
    }
}
