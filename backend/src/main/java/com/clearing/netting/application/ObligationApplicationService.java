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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ObligationApplicationService {

    private final ObligationRepositoryPort obligationRepository;
    private final MemberRepositoryPort memberRepository;
    private final ObligationCsvParser csvParser;

    public ObligationApplicationService(
            ObligationRepositoryPort obligationRepository,
            MemberRepositoryPort memberRepository,
            ObligationCsvParser csvParser) {
        this.obligationRepository = obligationRepository;
        this.memberRepository = memberRepository;
        this.csvParser = csvParser;
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

    /**
     * 导入义务 CSV：逐行校验，有效行批量写入（OPEN），问题行跳过并回显原因。
     */
    @Transactional
    public ObligationImportResult importCsv(String content) {
        Map<String, Member> members = memberRepository.findAll().stream()
                .collect(Collectors.toMap(Member::getMemberId, Function.identity()));
        ObligationCsvParser.ParsedBatch batch = csvParser.parse(content, members);

        List<TradeObligation> valid = batch.validObligations();
        if (!valid.isEmpty()) {
            obligationRepository.saveAll(valid);
        }

        List<ObligationImportResult.RowResult> rowResults = new ArrayList<>();
        int successCount = 0;
        for (ObligationCsvParser.ParsedRow row : batch.rows()) {
            if (row.success()) {
                successCount++;
            }
            rowResults.add(new ObligationImportResult.RowResult(
                    row.lineNumber(),
                    row.success(),
                    row.raw()[0],
                    row.raw()[1],
                    row.raw()[2],
                    row.raw()[3],
                    row.raw()[4],
                    row.raw()[5],
                    row.error()));
        }
        return new ObligationImportResult(rowResults.size(), successCount, rowResults);
    }

    private void validateMember(String memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new DomainException("MEMBER_NOT_FOUND", "member not found: " + memberId));
        if (member.getStatus() == MemberStatus.SUSPENDED) {
            throw new DomainException("SUSPENDED_MEMBER", "cannot create obligation for suspended member: " + memberId);
        }
    }
}
