package com.devhub.module.ai.mapper;

import com.devhub.module.ai.model.entity.DocumentChunkEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 文档切片 Mapper
 * <p>
 * 提供基于 MySQL ngram 全文索引的关键词检索通道
 */
@Mapper
public interface DocumentChunkMapper extends BaseMapper<DocumentChunkEntity> {

    /**
     * 全文关键词检索（NATURAL LANGUAGE MODE，按相关度排序；仅检索有效知识）
     * <p>在父块粒度打分：BM25 的词频区分度需要足够文本长度，短子块会让高频主题词失焦
     */
    @Select("SELECT id, document_id, file_name, content, " +
            "MATCH(content) AGAINST(#{keyword} IN NATURAL LANGUAGE MODE) AS score " +
            "FROM document_chunk " +
            "WHERE deleted = 0 AND doc_status IN ('active','draft') " +
            "AND (chunk_type = 1 OR chunk_type IS NULL) " +
            "AND MATCH(content) AGAINST(#{keyword} IN NATURAL LANGUAGE MODE) " +
            "LIMIT #{limit}")
    List<DocumentChunkEntity> searchByKeyword(@Param("keyword") String keyword, @Param("limit") int limit);

    /**
     * 精确短语匹配（第三层检索：exact match，如"第十三条"、具体编号）
     */
    @Select("SELECT id, document_id, file_name, content, 1.0 AS score " +
            "FROM document_chunk " +
            "WHERE deleted = 0 AND doc_status IN ('active','draft') " +
            "AND (chunk_type = 0 OR chunk_type IS NULL) " +
            "AND content LIKE CONCAT('%', #{phrase}, '%') " +
            "LIMIT #{limit}")
    List<DocumentChunkEntity> searchExact(@Param("phrase") String phrase, @Param("limit") int limit);
}
