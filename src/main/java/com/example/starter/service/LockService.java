package com.example.starter.service;

import com.example.starter.web.dto.CreateLockRequest;
import com.example.starter.web.dto.LockFileResponse;

import java.util.List;

/**
 * 依赖锁定与历史锁文件查询服务。
 */
public interface LockService {

    LockFileResponse createLock(CreateLockRequest request);

    LockFileResponse getLock(long id);

    List<LockFileResponse> listLocks(String rootName);
}
