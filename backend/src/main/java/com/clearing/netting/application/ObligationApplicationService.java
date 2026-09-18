package com.clearing.netting.application;

import com.clearing.netting.domain.exception.DomainException;
import com.clearing.netting.domain.model.Member;
import com.clearing.netting.domain.model.MemberStatus;
import com.clearing.netting.domain.model.ObligationStatus;
import com.clearing.netting.domain.model.TradeObligation;
import com.clearing.netting.domain.port.out.MemberRepositoryPort;
import com.clearing.netting.domain.port.out.ObligationRepositoryPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ObligationApplicationService {

    private final ObligationRepositoryPort obligationRepository;
    private final MemberRepositoryPort memberRepository;

    public ObligationApplicationService(
            ObligationRepositoryPort obligationRepository,
            MemberRepositoryPort memberRepository) {
        this.obligationRepository = obligationRepository;
        this.memberRepository = memberRepository;
    }

    @Transactional(readOnly = true)
    public List<TradeObligation> list(String currency, LocalDate settleDate, ObligationStatus status) {
        return obligationRepository.findByFilters(currency, settleDate, status);
    }

    @Transactional
    public TradeObligation create(
            String payerMemberId,
            String payeeMemberId,
            String currency,
            BigDecimal amount,
            LocalDate tradeDate,
            LocalDate settleDate) {
        validateMember(payerMemberId);
        validateMember(payeeMemberId);
        TradeObligation obligation = TradeObligation.open(
                payerMemberId, payeeMemberId, currency, amount, tradeDate, settleDate);
        return obligationRepository.save(obligation);
    }

    private void validateMember(String memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new DomainException("MEMBER_NOT_FOUND", "member not found: " + memberId));
        if (member.getStatus() == MemberStatus.SUSPENDED) {
            throw new DomainException("SUSPENDED_MEMBER", "cannot create obligation for suspended member: " + memberId);
        }
    }

    public record CsvImportResult(int totalRows, int successCount, List<CsvRowError> errors,
                                  List<TradeObligation> imported) {
    }

    public record CsvRowError(int lineNumber, String rawLine, String reason) {
    }

    private static final String[] EXPECTED_HEADER = {
            "payerMemberId", "payeeMemberId", "currency", "amount", "tradeDate", "settleDate"};

    /**
     * 逐行解析并导入义务。合法行以 OPEN 状态落库，非法行被跳过并记录行号与原因，
     * 因此好坏行混杂时合法行仍会成功导入。
     */
    @Transactional
    public CsvImportResult importCsv(byte[] content) {
        List<String> lines;
        try {
            lines = new BufferedReader(new StringReader(new String(content, StandardCharsets.UTF_8)))
                    .lines().collect(Collectors.toList());
        } catch (RuntimeException ex) {
            throw new DomainException("CSV_INVALID", "cannot read csv: " + ex.getMessage());
        }

        int startIndex = 0;
        if (!lines.isEmpty() && isHeader(lines.get(0))) {
            startIndex = 1;
        }

        // 预取全部会员，避免逐行查库；状态校验也基于此快照。
        Map<String, Member> memberCache = memberRepository.findAll().stream()
                .collect(Collectors.toMap(Member::getMemberId, m -> m));

        List<TradeObligation> valid = new ArrayList<>();
        List<CsvRowError> errors = new ArrayList<>();
        int totalRows = 0;

        for (int i = startIndex; i < lines.size(); i++) {
            int lineNumber = i + 1;
            String rawLine = lines.get(i);
            if (rawLine == null || rawLine.isBlank()) {
                continue;
            }
            totalRows++;
            try {
                valid.add(parseAndBuild(rawLine, memberCache));
            } catch (IllegalArgumentException | DomainException ex) {
                errors.add(new CsvRowError(lineNumber, rawLine, ex.getMessage()));
            }
        }

        List<TradeObligation> imported = valid.isEmpty() ? List.of() : obligationRepository.saveAll(valid);
        return new CsvImportResult(totalRows, imported.size(), errors, imported);
    }

    private boolean isHeader(String line) {
        String[] cells = splitCsv(line);
        if (cells.length != EXPECTED_HEADER.length) {
            return false;
        }
        for (int i = 0; i < EXPECTED_HEADER.length; i++) {
            if (!EXPECTED_HEADER[i].equalsIgnoreCase(cells[i].trim())) {
                return false;
            }
        }
        return true;
    }

    private TradeObligation parseAndBuild(String rawLine, Map<String, Member> memberCache) {
        String[] cells = splitCsv(rawLine);
        if (cells.length != 6) {
            throw new IllegalArgumentException("expected 6 columns, got " + cells.length);
        }
        String payerMemberId = cells[0].trim();
        String payeeMemberId = cells[1].trim();
        String currency = cells[2].trim();
        String amountText = cells[3].trim();
        String tradeDateText = cells[4].trim();
        String settleDateText = cells[5].trim();

        if (payerMemberId.isBlank() || payeeMemberId.isBlank() || currency.isBlank()
                || amountText.isBlank() || tradeDateText.isBlank() || settleDateText.isBlank()) {
            throw new IllegalArgumentException("all columns are required");
        }

        BigDecimal amount;
        try {
            amount = new BigDecimal(amountText);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("invalid amount: " + amountText);
        }

        LocalDate tradeDate;
        LocalDate settleDate;
        try {
            tradeDate = LocalDate.parse(tradeDateText);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("invalid tradeDate, expected YYYY-MM-DD: " + tradeDateText);
        }
        try {
            settleDate = LocalDate.parse(settleDateText);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("invalid settleDate, expected YYYY-MM-DD: " + settleDateText);
        }

        validateMemberFromCache(payerMemberId, memberCache);
        validateMemberFromCache(payeeMemberId, memberCache);

        return TradeObligation.open(payerMemberId, payeeMemberId, currency, amount, tradeDate, settleDate);
    }

    private void validateMemberFromCache(String memberId, Map<String, Member> memberCache) {
        Member member = memberCache.get(memberId);
        if (member == null) {
            throw new DomainException("MEMBER_NOT_FOUND", "member not found: " + memberId);
        }
        if (member.getStatus() == MemberStatus.SUSPENDED) {
            throw new DomainException("SUSPENDED_MEMBER", "suspended member: " + memberId);
        }
    }

    /** 极简 CSV 拆分：按逗号切分并去掉每段两端空白（不支持字段内逗号/引号转义）。 */
    private String[] splitCsv(String line) {
        String stripped = line.strip();
        if (stripped.isEmpty()) {
            return new String[0];
        }
        return stripped.split(",", -1);
    }
}
