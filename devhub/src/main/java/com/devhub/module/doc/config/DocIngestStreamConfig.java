package com.devhub.module.doc.config;

import com.devhub.module.doc.service.DocIngestStreamConsumer;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;

import java.time.Duration;

/**
 * 文档入库 Redis Stream 基础设施
 * <p>
 * Stream: devhub:stream:doc-ingest；消费者组 doc-ingest（手动 ACK，至少一次投递）。
 * 启动时确保组存在（BUSYGROUP = 已创建，幂等），监听容器持续 XREADGROUP。
 */
@Configuration
@RequiredArgsConstructor
public class DocIngestStreamConfig {

    public static final String STREAM_KEY = "devhub:stream:doc-ingest";
    public static final String GROUP = "doc-ingest";
    public static final String CONSUMER = "consumer-1";
    /** Stream 限长（近似裁剪），防无限增长 */
    public static final long MAX_STREAM_LEN = 10_000;

    private final StringRedisTemplate redisTemplate;

    @Bean
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>> docIngestStreamContainer(
            RedisConnectionFactory connectionFactory, DocIngestStreamConsumer consumer) {

        ensureGroup();

        StreamMessageListenerContainer.StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                StreamMessageListenerContainer.StreamMessageListenerContainerOptions.builder()
                        .pollTimeout(Duration.ofSeconds(2))
                        .batchSize(10)
                        .build();

        StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
                StreamMessageListenerContainer.create(connectionFactory, options);
        container.receive(Consumer.from(GROUP, CONSUMER),
                StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()),
                consumer);
        container.start();
        return container;
    }

    /** 消费者组不存在则创建（底层带 MKSTREAM，流不存在会建空流；从 0 读起重启不丢消息）；已存在忽略 */
    private void ensureGroup() {
        try {
            redisTemplate.opsForStream().createGroup(STREAM_KEY, ReadOffset.from("0"), GROUP);
        } catch (RedisSystemException e) {
            if (!String.valueOf(e.getCause()).contains("BUSYGROUP")) {
                throw e;
            }
        }
    }
}
