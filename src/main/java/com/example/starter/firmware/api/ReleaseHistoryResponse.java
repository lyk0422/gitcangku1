package com.example.starter.firmware.api;

import java.util.List;

/**
 * 发布单暂停/恢复历史视图（只读）。
 */
public record ReleaseHistoryResponse(long releaseId, List<PauseRecordView> pauses,
                                     List<ResumeRecordView> resumes) {
}
