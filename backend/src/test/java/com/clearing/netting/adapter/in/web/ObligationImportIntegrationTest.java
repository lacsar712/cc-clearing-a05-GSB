package com.clearing.netting.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ObligationImportIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    private final String header = "payerMemberId,payeeMemberId,currency,amount,tradeDate,settleDate\n";

    @Test
    void mixedRows_validRowsPersisted_openAndFailuresReturned() throws Exception {
        String token = login("operator", "op123456");
        String m1 = createMember(token, "Imp Alpha");
        String m2 = createMember(token, "Imp Beta");

        String csv = header
                + m1 + "," + m2 + ",USD,1000,2026-09-18,2026-09-19\n"
                + m1 + ",NO_SUCH_MEMBER,USD,1000,2026-09-18,2026-09-19\n"
                + m1 + "," + m2 + ",USD,0,2026-09-18,2026-09-19\n"
                + m2 + "," + m1 + ",EUR,250.5,2026-09-18,2026-09-20\n";

        mvc.perform(multipart("/api/obligations/import")
                        .file(csvFile(csv))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRows").value(4))
                .andExpect(jsonPath("$.successCount").value(2))
                .andExpect(jsonPath("$.failureCount").value(2))
                .andExpect(jsonPath("$.rows[0].success").value(true))
                .andExpect(jsonPath("$.rows[0].lineNumber").value(2))
                .andExpect(jsonPath("$.rows[1].success").value(false))
                .andExpect(jsonPath("$.rows[1].error").isNotEmpty())
                .andExpect(jsonPath("$.rows[2].success").value(false))
                .andExpect(jsonPath("$.rows[3].success").value(true));

        // 成功行可在义务列表（OPEN）中看到
        MvcResult listResult = mvc.perform(get("/api/obligations").param("status", "OPEN")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode obligations = objectMapper.readTree(listResult.getResponse().getContentAsString());
        boolean foundUsd = false;
        boolean foundEur = false;
        for (JsonNode o : obligations) {
            if (m1.equals(o.get("payerMemberId").asText())
                    && m2.equals(o.get("payeeMemberId").asText())
                    && "USD".equals(o.get("currency").asText())
                    && o.get("amount").decimalValue().compareTo(new java.math.BigDecimal("1000")) == 0) {
                foundUsd = true;
            }
            if (m2.equals(o.get("payerMemberId").asText())
                    && m1.equals(o.get("payeeMemberId").asText())
                    && "EUR".equals(o.get("currency").asText())) {
                foundEur = true;
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(foundUsd, "导入的 USD 义务应出现在 OPEN 列表");
        org.junit.jupiter.api.Assertions.assertTrue(foundEur, "导入的 EUR 义务应出现在 OPEN 列表");
    }

    @Test
    void viewerCannotImport() throws Exception {
        String token = login("viewer", "view123456");
        mvc.perform(multipart("/api/obligations/import")
                        .file(csvFile(header + "A,B,USD,1,2026-09-18,2026-09-19\n"))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedCannotImport() throws Exception {
        mvc.perform(multipart("/api/obligations/import")
                        .file(csvFile(header + "A,B,USD,1,2026-09-18,2026-09-19\n")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void structurallyInvalidCsvIsBadRequest() throws Exception {
        String token = login("operator", "op123456");
        mvc.perform(multipart("/api/obligations/import")
                        .file(csvFile("payerMemberId,payeeMemberId,currency,amount,tradeDate\n"
                                + "A,B,USD,1,2026-09-18\n"))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CSV"));
    }

    private MockMultipartFile csvFile(String content) {
        return new MockMultipartFile(
                "file", "obligations.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private String createMember(String token, String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/members")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("memberId").asText();
    }
}
