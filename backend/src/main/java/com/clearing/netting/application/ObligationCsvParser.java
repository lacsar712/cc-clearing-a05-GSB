package com.clearing.netting.application;

import com.clearing.netting.domain.exception.DomainException;
import com.clearing.netting.domain.model.Member;
import com.clearing.netting.domain.model.TradeObligation;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 义务 CSV 解析与逐行校验：结构性问题（缺列、无数据行）直接抛 DomainException，
 * 单行数据问题收集到 ParsedRow.error，由调用方决定写入与回显。
 */
@Component
public class ObligationCsvParser {

    public static final List<String> COLUMNS = List.of(
            "payerMemberId", "payeeMemberId", "currency", "amount", "tradeDate", "settleDate");

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("payermemberid", "payerMemberId"),
            Map.entry("payer", "payerMemberId"),
            Map.entry("付款方", "payerMemberId"),
            Map.entry("付款方会员id", "payerMemberId"),
            Map.entry("付款方会员编号", "payerMemberId"),
            Map.entry("payeememberid", "payeeMemberId"),
            Map.entry("payee", "payeeMemberId"),
            Map.entry("收款方", "payeeMemberId"),
            Map.entry("收款方会员id", "payeeMemberId"),
            Map.entry("收款方会员编号", "payeeMemberId"),
            Map.entry("currency", "currency"),
            Map.entry("ccy", "currency"),
            Map.entry("币种", "currency"),
            Map.entry("amount", "amount"),
            Map.entry("金额", "amount"),
            Map.entry("tradedate", "tradeDate"),
            Map.entry("trade_date", "tradeDate"),
            Map.entry("交易日", "tradeDate"),
            Map.entry("settledate", "settleDate"),
            Map.entry("settle_date", "settleDate"),
            Map.entry("交割日", "settleDate"));

    public ParsedBatch parse(String content, Map<String, Member> members) {
        if (content == null || content.isBlank()) {
            throw new DomainException("INVALID_CSV", "CSV 内容为空");
        }
        String text = content;
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = text.split("\n", -1);
        if (lines.length == 0 || lines[0].isBlank()) {
            throw new DomainException("INVALID_CSV", "CSV 缺少表头");
        }

        List<String> headerCells;
        try {
            headerCells = parseLine(lines[0]);
        } catch (IllegalArgumentException e) {
            throw new DomainException("INVALID_CSV", "CSV 表头格式错误: " + e.getMessage());
        }
        Map<String, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < headerCells.size(); i++) {
            String column = ALIASES.get(headerCells.get(i).trim().toLowerCase());
            if (column != null) {
                index.putIfAbsent(column, i);
            }
        }
        List<String> missing = new ArrayList<>();
        for (String column : COLUMNS) {
            if (!index.containsKey(column)) {
                missing.add(column);
            }
        }
        if (!missing.isEmpty()) {
            throw new DomainException("INVALID_CSV", "CSV 缺少必需列: " + String.join(", ", missing));
        }

        List<ParsedRow> rows = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            int lineNumber = i + 1;
            String line = lines[i];
            if (line.isBlank()) {
                continue;
            }
            List<String> cells;
            try {
                cells = parseLine(line);
            } catch (IllegalArgumentException e) {
                rows.add(ParsedRow.failure(lineNumber, blankFields(), "CSV 格式错误: " + e.getMessage()));
                continue;
            }
            String[] raw = extract(cells, index);
            String error = validate(raw, members);
            if (error != null) {
                rows.add(ParsedRow.failure(lineNumber, raw, error));
                continue;
            }
            try {
                TradeObligation obligation = TradeObligation.open(
                        raw[0].trim(),
                        raw[1].trim(),
                        raw[2].trim().toUpperCase(),
                        new BigDecimal(raw[3].trim()),
                        LocalDate.parse(raw[4].trim()),
                        LocalDate.parse(raw[5].trim()));
                rows.add(ParsedRow.ok(lineNumber, raw, obligation));
            } catch (IllegalArgumentException e) {
                rows.add(ParsedRow.failure(lineNumber, raw, "行数据无效: " + e.getMessage()));
            }
        }
        if (rows.isEmpty()) {
            throw new DomainException("INVALID_CSV", "CSV 文件没有数据行");
        }
        return new ParsedBatch(rows);
    }

    private String validate(String[] raw, Map<String, Member> members) {
        String[] labels = {"付款方", "收款方", "币种", "金额", "交易日", "交割日"};
        for (int i = 0; i < raw.length; i++) {
            if (raw[i] == null || raw[i].isBlank()) {
                return labels[i] + "不能为空";
            }
        }
        String payer = raw[0].trim();
        String payee = raw[1].trim();
        String currency = raw[2].trim();

        if (!currency.matches("[A-Za-z]{3}")) {
            return "币种格式应为 3 位字母: " + currency;
        }
        Member payerMember = members.get(payer);
        if (payerMember == null) {
            return "付款方会员不存在: " + payer;
        }
        if (!payerMember.isActive()) {
            return "付款方会员已暂停: " + payer;
        }
        Member payeeMember = members.get(payee);
        if (payeeMember == null) {
            return "收款方会员不存在: " + payee;
        }
        if (!payeeMember.isActive()) {
            return "收款方会员已暂停: " + payee;
        }
        if (payer.equals(payee)) {
            return "付款方与收款方不能相同";
        }
        try {
            if (new BigDecimal(raw[3].trim()).compareTo(BigDecimal.ZERO) <= 0) {
                return "金额必须大于 0";
            }
        } catch (NumberFormatException e) {
            return "金额格式错误: " + raw[3].trim();
        }
        try {
            LocalDate.parse(raw[4].trim());
        } catch (DateTimeParseException e) {
            return "交易日格式错误（应为 YYYY-MM-DD）: " + raw[4].trim();
        }
        try {
            LocalDate.parse(raw[5].trim());
        } catch (DateTimeParseException e) {
            return "交割日格式错误（应为 YYYY-MM-DD）: " + raw[5].trim();
        }
        return null;
    }

    private String[] extract(List<String> cells, Map<String, Integer> index) {
        String[] raw = new String[COLUMNS.size()];
        for (int i = 0; i < COLUMNS.size(); i++) {
            int pos = index.get(COLUMNS.get(i));
            raw[i] = pos < cells.size() ? cells.get(pos).trim() : "";
        }
        return raw;
    }

    private static String[] blankFields() {
        return new String[]{"", "", "", "", "", ""};
    }

    /**
     * 解析单行 CSV，支持双引号包裹与 "" 转义；行内引号未闭合时抛异常。
     */
    static List<String> parseLine(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                result.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        if (inQuotes) {
            throw new IllegalArgumentException("引号未闭合");
        }
        result.add(field.toString());
        return result;
    }

    public record ParsedRow(
            int lineNumber,
            String[] raw,
            TradeObligation obligation,
            String error) {

        static ParsedRow ok(int lineNumber, String[] raw, TradeObligation obligation) {
            return new ParsedRow(lineNumber, raw, obligation, null);
        }

        static ParsedRow failure(int lineNumber, String[] raw, String error) {
            return new ParsedRow(lineNumber, raw, null, error);
        }

        public boolean success() {
            return error == null;
        }
    }

    public record ParsedBatch(List<ParsedRow> rows) {

        public List<TradeObligation> validObligations() {
            return rows.stream()
                    .filter(ParsedRow::success)
                    .map(ParsedRow::obligation)
                    .toList();
        }
    }
}
