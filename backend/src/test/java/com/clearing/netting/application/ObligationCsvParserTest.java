package com.clearing.netting.application;

import com.clearing.netting.domain.exception.DomainException;
import com.clearing.netting.domain.model.Member;
import com.clearing.netting.domain.model.MemberStatus;
import com.clearing.netting.domain.model.TradeObligation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObligationCsvParserTest {

    private ObligationCsvParser parser;
    private Map<String, Member> members;

    private static final String HEADER =
            "payerMemberId,payeeMemberId,currency,amount,tradeDate,settleDate\n";

    @BeforeEach
    void setUp() {
        parser = new ObligationCsvParser();
        members = Map.of(
                "M1", new Member("M1", "Alpha", MemberStatus.ACTIVE),
                "M2", new Member("M2", "Beta", MemberStatus.ACTIVE),
                "M3", new Member("M3", "Gamma", MemberStatus.SUSPENDED));
    }

    @Test
    void mixedGoodAndBadRowsAreSeparated() {
        String csv = HEADER
                + "M1,M2,USD,1000,2026-09-18,2026-09-19\n"       // 行 2 成功
                + "M1,M9,USD,1000,2026-09-18,2026-09-19\n"       // 行 3 收款方不存在
                + "M1,M2,USD,0,2026-09-18,2026-09-19\n"          // 行 4 金额非正
                + "M1,M2,USD,abc,2026-09-18,2026-09-19\n"        // 行 5 金额格式错误
                + "M1,M2,USD,500,18/09/2026,2026-09-19\n"        // 行 6 日期格式错误
                + "M1,M1,USD,500,2026-09-18,2026-09-19\n"        // 行 7 付收款相同
                + "M3,M2,USD,500,2026-09-18,2026-09-19\n"        // 行 8 付款方已暂停
                + "M2,M1,CNY,250.5,2026-09-18,2026-09-20\n";     // 行 9 成功

        ObligationCsvParser.ParsedBatch batch = parser.parse(csv, members);

        List<ObligationCsvParser.ParsedRow> rows = batch.rows();
        assertEquals(8, rows.size());
        assertEquals(2, batch.validObligations().size());

        assertTrue(rows.get(0).success());
        assertEquals(2, rows.get(0).lineNumber());

        assertFalse(rows.get(1).success());
        assertTrue(rows.get(1).error().contains("收款方会员不存在"));
        assertEquals(3, rows.get(1).lineNumber());

        assertFalse(rows.get(2).success());
        assertTrue(rows.get(2).error().contains("金额必须大于 0"));

        assertFalse(rows.get(3).success());
        assertTrue(rows.get(3).error().contains("金额格式错误"));

        assertFalse(rows.get(4).success());
        assertTrue(rows.get(4).error().contains("交易日格式错误"));

        assertFalse(rows.get(5).success());
        assertTrue(rows.get(5).error().contains("不能相同"));

        assertFalse(rows.get(6).success());
        assertTrue(rows.get(6).error().contains("付款方会员已暂停"));

        assertTrue(rows.get(7).success());
        TradeObligation second = rows.get(7).obligation();
        assertEquals("CNY", second.getCurrency());
    }

    @Test
    void missingRequiredColumnIsRejected() {
        String csv = "payerMemberId,payeeMemberId,currency,amount,tradeDate\n"
                + "M1,M2,USD,1000,2026-09-18\n";
        DomainException ex = assertThrows(DomainException.class, () -> parser.parse(csv, members));
        assertEquals("INVALID_CSV", ex.getCode());
        assertTrue(ex.getMessage().contains("settleDate"));
    }

    @Test
    void emptyFileIsRejected() {
        DomainException ex = assertThrows(DomainException.class, () -> parser.parse("   \n", members));
        assertEquals("INVALID_CSV", ex.getCode());
    }

    @Test
    void chineseHeaderAliasesAreAccepted() {
        String csv = "付款方,收款方,币种,金额,交易日,交割日\n"
                + "M1,M2,USD,1000,2026-09-18,2026-09-19\n";
        ObligationCsvParser.ParsedBatch batch = parser.parse(csv, members);
        assertEquals(1, batch.validObligations().size());
    }

    @Test
    void blankFieldsAreReported() {
        String csv = HEADER
                + "M1,,USD,1000,2026-09-18,2026-09-19\n"
                + ",M2,,1000,2026-09-18,2026-09-19\n";
        ObligationCsvParser.ParsedBatch batch = parser.parse(csv, members);
        assertEquals(0, batch.validObligations().size());
        assertTrue(batch.rows().get(0).error().contains("收款方不能为空"));
        assertTrue(batch.rows().get(1).error().contains("付款方不能为空"));
    }
}
