package com.clearing.netting.adapter.in.web;

import com.clearing.netting.adapter.in.web.auth.AuthContext;
import com.clearing.netting.adapter.in.web.auth.AuthUser;
import com.clearing.netting.application.ObligationApplicationService;
import com.clearing.netting.domain.model.Member;
import com.clearing.netting.domain.model.MemberStatus;
import com.clearing.netting.domain.model.ObligationStatus;
import com.clearing.netting.domain.model.TradeObligation;
import com.clearing.netting.domain.port.out.MemberRepositoryPort;
import com.clearing.netting.domain.port.out.ObligationRepositoryPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ObligationControllerImportTest {

    private final Map<String, Member> members = new HashMap<>();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        members.put("M1", new Member("M1", "A", MemberStatus.ACTIVE));
        members.put("M2", new Member("M2", "B", MemberStatus.ACTIVE));

        ObligationApplicationService service =
                new ObligationApplicationService(new FakeObligationRepository(), new FakeMemberRepository());
        mvc = MockMvcBuilders.standaloneSetup(new ObligationController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clearAuth() {
        AuthContext.clear();
    }

    @Test
    void operatorUploadingMixedCsv_seesSuccessCountAndFailedLines() throws Exception {
        AuthContext.set(new AuthUser("operator", "OPERATOR"));
        String csv = String.join("\n",
                "payerMemberId,payeeMemberId,currency,amount,tradeDate,settleDate",
                "M1,M2,USD,100,2026-09-01,2026-09-02",
                "M1,NOPE,USD,50,2026-09-01,2026-09-02",
                "M1,M2,EUR,7,2026-09-01,2026-09-02");
        MockMultipartFile file = new MockMultipartFile(
                "file", "ob.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/api/obligations/import").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRows").value(3))
                .andExpect(jsonPath("$.successCount").value(2))
                .andExpect(jsonPath("$.failureCount").value(1))
                .andExpect(jsonPath("$.errors[0].lineNumber").value(3))
                .andExpect(jsonPath("$.errors[0].reason").value(org.hamcrest.Matchers.containsString("member not found")));
    }

    @Test
    void viewerIsForbidden() throws Exception {
        AuthContext.set(new AuthUser("viewer", "VIEWER"));
        MockMultipartFile file = new MockMultipartFile(
                "file", "ob.csv", "text/csv",
                "M1,M2,USD,100,2026-09-01,2026-09-02".getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/api/obligations/import").file(file))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void emptyFileIsRejected() throws Exception {
        AuthContext.set(new AuthUser("operator", "OPERATOR"));
        MockMultipartFile file = new MockMultipartFile("file", "empty.csv", "text/csv", new byte[0]);

        mvc.perform(multipart("/api/obligations/import").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CSV_EMPTY"));
    }

    private class FakeMemberRepository implements MemberRepositoryPort {
        @Override public Member save(Member member) { return member; }
        @Override public Optional<Member> findById(String id) { return Optional.ofNullable(members.get(id)); }
        @Override public List<Member> findAll() { return new ArrayList<>(members.values()); }
        @Override public List<Member> findByIds(Iterable<String> ids) { return List.of(); }
    }

    private class FakeObligationRepository implements ObligationRepositoryPort {
        private final List<TradeObligation> store = new ArrayList<>();
        @Override public TradeObligation save(TradeObligation o) { store.add(o); return o; }
        @Override public List<TradeObligation> saveAll(List<TradeObligation> os) { store.addAll(os); return os; }
        @Override public Optional<TradeObligation> findById(String id) { return Optional.empty(); }
        @Override public List<TradeObligation> findAll() { return store; }
        @Override public List<TradeObligation> findByFilters(String c, LocalDate d, ObligationStatus s) { return store; }
        @Override public List<TradeObligation> findOpenBySettleDateAndCurrency(LocalDate d, String c) { return store; }
        @Override public List<TradeObligation> findByNettingRunId(String runId) { return store; }
    }
}
