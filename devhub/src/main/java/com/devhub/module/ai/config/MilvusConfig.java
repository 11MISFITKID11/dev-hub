package com.devhub.module.ai.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Milvus 向量数据库配置类
 */
@Slf4j
@Getter
@Configuration
public class MilvusConfig {

    @Value("${milvus.host}")
    private String host;

    @Value("${milvus.port}")
    private int port;

    @Value("${milvus.collection-name}")
    private String collectionName;

    @Value("${milvus.dimension}")
    private int dimension;

    @Value("${milvus.index-type:IVF_FLAT}")
    private String indexType;

    @Value("${milvus.metric-type:COSINE}")
    private String metricType;

    @Value("${milvus.nlist:1024}")
    private int nlist;

    @Bean
    public MilvusServiceClient milvusServiceClient() {
        ConnectParam connectParam = ConnectParam.newBuilder()
                .withHost(host)
                .withPort(port)
                .withConnectTimeout(10L, TimeUnit.SECONDS)
                .withKeepAliveTime(55L, TimeUnit.SECONDS)
                .withKeepAliveTimeout(20L, TimeUnit.SECONDS)
                .build();

        log.info("初始化 Milvus 连接: {}:{}", host, port);
        return new MilvusServiceClient(connectParam);
    }
}
