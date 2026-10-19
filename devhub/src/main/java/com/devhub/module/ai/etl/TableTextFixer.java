package com.devhub.module.ai.etl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 表格文本启发式修复器
 * <p>
 * PDF 经 Tika 抽取后表格结构丢失，单元格以连续空格/制表符平铺成行。
 * 本修复器把"连续多行、每行可按 2+ 空格或 Tab 切出多列"的文本段还原为
 * Markdown 表格（首行作表头），让结构感知切片器能按表格边界切分、
 * 检索时保留行列关系。
 * <p>
 * 保守策略：段内行数 ≥2、各行列数差 ≤1、行内无句末标点（排除正文段落）才转换，
 * 避免把普通正文误转成表格。
 */
@Slf4j
@Component
public class TableTextFixer {

    private static final Pattern CELL_SPLIT = Pattern.compile("\\s{2,}|\t");
    private static final Pattern SENTENCE_END = Pattern.compile("[。；？！.；]");

    /**
     * 修复文本中的平铺表格（无表格特征时原样返回）
     */
    public String fix(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String[] lines = text.split("\n", -1);
        List<String> out = new ArrayList<>(lines.length);
        int fixedTables = 0;

        int i = 0;
        while (i < lines.length) {
            int cols = columnCount(lines[i]);
            if (cols >= 2 && !looksLikeSentence(lines[i])) {
                // 收集连续的表格行
                int j = i + 1;
                List<String> block = new ArrayList<>();
                block.add(lines[i]);
                while (j < lines.length) {
                    int nextCols = columnCount(lines[j]);
                    if (nextCols >= 2 && Math.abs(nextCols - cols) <= 1 && !looksLikeSentence(lines[j])) {
                        block.add(lines[j]);
                        j++;
                    } else {
                        break;
                    }
                }
                if (block.size() >= 2) {
                    out.add(toMarkdownTable(block));
                    fixedTables++;
                    i = j;
                    continue;
                }
            }
            out.add(lines[i]);
            i++;
        }

        if (fixedTables > 0) {
            log.info("表格修复: 识别并还原 {} 个 Markdown 表格", fixedTables);
            return String.join("\n", out);
        }
        return text;
    }

    private int columnCount(String line) {
        String trimmed = line.strip();
        if (trimmed.length() < 4) {
            return 1;
        }
        return CELL_SPLIT.split(trimmed).length;
    }

    /** 含句末标点的行视为正文段落，不参与表格 */
    private boolean looksLikeSentence(String line) {
        String trimmed = line.strip();
        if (SENTENCE_END.matcher(trimmed).find()) {
            return true;
        }
        // 多列行（含分隔空隙/Tab）本身就是表格形态，不受最短长度限制；
        // 单列短行（如"1."式碎片）才视为普通文本
        return CELL_SPLIT.split(trimmed).length < 2 && trimmed.length() < 6;
    }

    private String toMarkdownTable(List<String> rows) {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < rows.size(); r++) {
            String[] cells = CELL_SPLIT.split(rows.get(r).strip());
            sb.append("|");
            for (String cell : cells) {
                sb.append(" ").append(cell.replace("|", "\\|").strip()).append(" |");
            }
            sb.append("\n");
            if (r == 0) {
                for (int c = 0; c < cells.length; c++) {
                    sb.append("|---");
                }
                sb.append("|\n");
            }
        }
        return sb.toString();
    }
}
