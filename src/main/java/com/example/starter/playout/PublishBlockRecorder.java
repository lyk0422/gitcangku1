package com.example.starter.playout;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 发布阻断审计记录器：在发布主事务之外以独立事务提交阻断明细，
 * 保证发布 422 回滚后仍可查询稳定的阻断区域与窗口。
 */
@Component
public class PublishBlockRecorder {

    private final PlayoutRepository repo;

    public PublishBlockRecorder(PlayoutRepository repo) {
        this.repo = repo;
    }

    /** 以独立事务写入一条阻断审计；外层发布事务回滚不影响本记录。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String channelId, LocalDate businessDay, String requestId,
                       String code, String detailJson) {
        repo.insertPublishBlock(channelId, businessDay, requestId, code, detailJson,
                System.currentTimeMillis());
    }
}
