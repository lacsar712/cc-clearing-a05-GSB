package com.clearing.netting.application;

import com.clearing.netting.domain.model.Member;
import com.clearing.netting.domain.model.MemberStatus;
import com.clearing.netting.domain.model.ObligationStatus;
import com.clearing.netting.domain.model.TradeObligation;
import com.clearing.netting.domain.port.out.MemberRepositoryPort;
import com.clearing.netting.domain.port.out.ObligationRepositoryPort;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ObligationApplicationServiceCsvImportTest {

    private final Map<String, Member> members = new HashMap<>();
    private final List<TradeObligation> saved = new ArrayList<>();

    private final ObligationApplicationService service = new ObligationApplicationService(
            new FakeObligationRepository(), new FakeMemberRepository());

    private ObligationApplicationService.CsvImportResult importCsv(String content) {
        return service.importCsv(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void mixedGoodAndBadRows_validRowsAreOpenedAndBadRowsReportedWithLineNumbers() {
        members.put("M1", new Member("M1", "A", MemberStatus.ACTIVE));
        members.put("M2", new Member("M2", "B", MemberStatus.ACTIVE));
        members.put("M3", new Member("M3", "C", MemberStatus.SUSPENDED));

        String csv = String.join("\n",
                "payerMemberId,payeeMemberId,currency,amount,tradeDate,settleDate",
                "M1,M2,USD,100.50,2026-09-01,2026-09-02",          // line 2: valid
                "M1,M2,USD,notanumber,2026-09-01,2026-09-02",        // line 3: bad amount
                "M1,UNKNOWN,USD,50,2026-09-01,2026-09-02",           // line 4: unknown member
                "M1,M3,USD,50,2026-09-01,2026-09-02",                // line 5: suspended member
                "M1,M2,USD,50,bad-date,2026-09-02",                  // line 6: bad date
                "M1,M1,USD,50,2026-09-01,2026-09-02",                // line 7: payer == payee
                "M1,M2,EUR,7,2026-09-01,2026-09-02"                  // line 8: valid
        );

        ObligationApplicationService.CsvImportResult result = importCsv(csv);

        assertThat(result.totalRows()).isEqualTo(7);
        assertThat(result.successCount()).isEqualTo(2);
        assertThat(result.errors()).hasSize(5);
        assertThat(result.errors()).extracting(ObligationApplicationService.CsvRowError::lineNumber)
                .containsExactly(3, 4, 5, 6, 7);

        // valid rows persisted as OPEN and visible afterwards
        assertThat(saved).hasSize(2);
        assertThat(saved).allSatisfy(o -> assertThat(o.getStatus()).isEqualTo(ObligationStatus.OPEN));
        assertThat(saved).extracting(TradeObligation::getCurrency).containsExactly("USD", "EUR");
    }

    @Test
    void worksWithoutHeaderRow() {
        members.put("M1", new Member("M1", "A", MemberStatus.ACTIVE));
        members.put("M2", new Member("M2", "B", MemberStatus.ACTIVE));

        ObligationApplicationService.CsvImportResult result =
                importCsv("M1,M2,USD,10,2026-09-01,2026-09-02\nM1,M2,USD,20,2026-09-01,2026-09-02");

        assertThat(result.totalRows()).isEqualTo(2);
        assertThat(result.successCount()).isEqualTo(2);
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void blankLinesAreIgnored() {
        members.put("M1", new Member("M1", "A", MemberStatus.ACTIVE));
        members.put("M2", new Member("M2", "B", MemberStatus.ACTIVE));

        ObligationApplicationService.CsvImportResult result =
                importCsv("payerMemberId,payeeMemberId,currency,amount,tradeDate,settleDate\n"
                        + "\nM1,M2,USD,10,2026-09-01,2026-09-02\n  \n");

        assertThat(result.totalRows()).isEqualTo(1);
        assertThat(result.successCount()).isEqualTo(1);
    }

    private class FakeMemberRepository implements MemberRepositoryPort {
        @Override public Member save(Member member) { return member; }
        @Override public Optional<Member> findById(String memberId) { return Optional.ofNullable(members.get(memberId)); }
        @Override public List<Member> findAll() { return new ArrayList<>(members.values()); }
        @Override public List<Member> findByIds(Iterable<String> memberIds) { return List.of(); }
    }

    private class FakeObligationRepository implements ObligationRepositoryPort {
        @Override public TradeObligation save(TradeObligation o) { saved.add(o); return o; }
        @Override public List<TradeObligation> saveAll(List<TradeObligation> obligations) {
            saved.addAll(obligations);
            return obligations;
        }
        @Override public Optional<TradeObligation> findById(String obligationId) { return Optional.empty(); }
        @Override public List<TradeObligation> findAll() { return saved; }
        @Override public List<TradeObligation> findByFilters(String c, LocalDate d, ObligationStatus s) { return saved; }
        @Override public List<TradeObligation> findOpenBySettleDateAndCurrency(LocalDate d, String c) { return saved; }
        @Override public List<TradeObligation> findByNettingRunId(String runId) { return saved; }
    }
}
