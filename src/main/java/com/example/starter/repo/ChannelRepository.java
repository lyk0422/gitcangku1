package com.example.starter.repo;

import com.example.starter.domain.Channel;

import java.util.Optional;

/**
 * 频道持久化。
 */
public interface ChannelRepository {

    /**
     * 新增频道；ID 已存在时抛 {@link DuplicateKeyException}。
     */
    void insert(Channel channel);

    Optional<Channel> findById(String id);
}
