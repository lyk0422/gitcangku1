package com.example.starter.playout;

import com.example.starter.playout.api.Dtos.RatingViolationInfo;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 发布分级校验失败审计：发布事务因越级回滚后，以独立事务补写 FAIL 校验记录，
 * 使被拦截的发布尝试也可通过校验记录查询追溯；成功发布的 PASS 记录随发布事务写入。
 */
@Component
public class RatingCheckAudit {

    private final PlayoutRepository repo;

    public RatingCheckAudit(PlayoutRepository repo) {
        this.repo = repo;
    }

    /** 独立事务记录一次被拦截发布的全部越级明细（publicationId 为 NULL）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String channelId, LocalDate businessDay,
                              List<RatingViolationInfo> violations) {
        long nowMs = System.currentTimeMillis();
        for (RatingViolationInfo violation : violations) {
            repo.insertRatingCheck(null, channelId, businessDay,
                    violation.segmentId(), violation.assetId(), violation.rating(),
                    violation.windowId(), violation.windowMaxRating(), "FAIL", nowMs);
        }
    }
}
