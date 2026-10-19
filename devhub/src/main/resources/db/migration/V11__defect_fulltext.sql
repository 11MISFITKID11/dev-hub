-- P2-8 相似缺陷检索依赖缺陷标题 ngram 全文索引
ALTER TABLE prj_defect ADD FULLTEXT KEY ft_title (title) /*!50100 WITH PARSER `ngram` */;
