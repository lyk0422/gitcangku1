package com.example.starter.tag.repository;

import com.example.starter.tag.domain.Attachment;
import com.example.starter.tag.domain.Handoff;
import com.example.starter.tag.domain.HandoffStatus;
import com.example.starter.tag.domain.IdempotentRequest;
import com.example.starter.tag.domain.Tag;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 标签业务的数据访问接口；实现基于 JdbcTemplate，全部 SQL 使用参数绑定。
 */
public interface TagRepository {

    Optional<Tag> findTagByCode(String tagCode);

    Tag findTagById(long tagId);

    Tag getTagByCode(String tagCode);

    Long findSiteIdByCode(String siteCode);

    long requireSiteId(String siteCode);

    Long findAnimalIdByCode(String animalCode);

    long requireAnimalId(String animalCode);

    String findSiteCodeById(long siteId);

    String findAnimalCodeById(long animalId);

    void insertTag(String tagCode, long holderSiteId, Instant registeredAt);

    /** 对标签行加行级写锁（SELECT ... FOR UPDATE），串行化附着/摘除/交接确认。 */
    void lockTag(long tagId);

    /** 对动物行加行级写锁，串行化同一动物的并发附着。 */
    void lockAnimal(long animalId);

    /** 条件更新标签持有站点；影响行数为 0 表示并发下持有权已变化。 */
    int updateTagHolder(long tagId, long expectedHolderSiteId, long newHolderSiteId);

    long insertAttachment(long animalId, long tagId, long siteId, Instant attachAt);

    Attachment getAttachmentById(long attachmentId);

    Optional<Attachment> findOpenAttachmentByTag(long tagId);

    /** 该标签全部附着记录中最晚的附着起始时刻；无记录时为 null，用于拒绝时间倒退。 */
    Instant findLatestAttachAtByTag(long tagId);

    /**
     * 冻结标签当前附着区间；要求区间起始时刻早于 detachAt。
     * 返回影响行数：0 表示无有效区间或时间倒退。
     */
    int closeAttachment(long attachmentId, long detachSiteId, Instant detachAt);

    List<Attachment> findAttachmentsByTag(long tagId);

    List<Attachment> findAttachmentsByAnimal(long animalId);

    /** 判断标签在给定时刻（含起点、不含终点的区间语义）是否已被占用。 */
    boolean tagOccupiedAt(long tagId, Instant at);

    /** 判断动物在给定时刻是否已佩戴其他有效标签。 */
    boolean animalOccupiedAt(long animalId, Instant at);

    Optional<Handoff> findHandoffByCode(String handoffCode);

    Optional<Handoff> findPendingHandoffByTag(long tagId);

    Handoff getHandoffByCode(String handoffCode);

    long insertHandoff(String handoffCode, long tagId, long fromSiteId, long toSiteId, Instant initiatedAt);

    Handoff getHandoffById(long handoffId);

    /** 条件状态流转；影响行数为 0 表示单据状态已被并发改变。 */
    int updateHandoffStatus(long handoffId, HandoffStatus expected, HandoffStatus next,
                            Instant confirmedAt, Instant closedAt, String rejectReason);

    List<Handoff> findHandoffsByTag(long tagId);

    /** 插入幂等键（result_id 先置 0），唯一冲突说明同 requestId 已被其他事务抢先提交。 */
    void insertIdempotentRequest(String requestId, String requestType, String requestHash);

    /** 业务记录落库后回填其主键。 */
    void updateIdempotentResult(String requestId, String requestType, long resultId);

    Optional<IdempotentRequest> findIdempotentRequest(String requestId, String requestType);
}
