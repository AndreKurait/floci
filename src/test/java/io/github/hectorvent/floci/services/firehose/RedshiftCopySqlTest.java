package io.github.hectorvent.floci.services.firehose;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedshiftCopySqlTest {

    @Test
    void qualifiedAndQuotedTableNamesAreAccepted() {
        for (String table : new String[] {"events", "Public.MyEvents", "\"my schema\".\"t\""}) {
            assertTrue(RedshiftCopySql.isValidTableName(table), table);
        }
    }

    @Test
    void tableNameThatIsNotAnIdentifierIsRejected() {
        for (String table : new String[] {"t; DROP TABLE x", "a.b.c", "ev\"il", "t (id)", "", null}) {
            assertFalse(RedshiftCopySql.isValidTableName(table), String.valueOf(table));
        }
    }

    @Test
    void columnThatIsNotAnIdentifierIsRejected() {
        assertFalse(RedshiftCopySql.isValidColumnList("id, name); DROP TABLE t; --"));
        assertTrue(RedshiftCopySql.isValidColumnList("Id, \"Full Name\""));
        assertTrue(RedshiftCopySql.isValidColumnList(null));
    }

    @Test
    void statementSeparatorOutsideQuotesIsDetected() {
        assertTrue(RedshiftCopySql.hasStatementSeparator("gzip; DROP TABLE t"));
    }

    @Test
    void semicolonInsideAQuotedCopyOptionValueIsAllowed() {
        assertFalse(RedshiftCopySql.hasStatementSeparator("DELIMITER ';' CSV"));
    }

    @Test
    void apiReferenceCopyOptionsExampleIsAccepted() {
        assertFalse(RedshiftCopySql.hasStatementSeparator("delimiter '\\t' lzop;"));
    }

    @Test
    void oneClosingTerminatorIsAcceptedWithWhitespaceAroundIt() {
        assertFalse(RedshiftCopySql.hasStatementSeparator("gzip ;"));
        assertFalse(RedshiftCopySql.hasStatementSeparator("gzip;  \n"));
    }

    @Test
    void terminatorFollowedByAnythingIsRefused() {
        assertTrue(RedshiftCopySql.hasStatementSeparator("gzip;;"));
        assertTrue(RedshiftCopySql.hasStatementSeparator("gzip; DROP TABLE t;"));
        assertTrue(RedshiftCopySql.hasStatementSeparator("gzip; -- x"));
    }

    @Test
    void terminatorInsideAnUnclosedLiteralIsRefused() {
        assertTrue(RedshiftCopySql.hasStatementSeparator("NULL AS 'a;"));
    }

    @Test
    void withoutTrailingTerminatorDropsOnlyTheClosingSemicolon() {
        assertEquals("gzip", RedshiftCopySql.withoutTrailingTerminator("gzip ; "));
        assertEquals("DELIMITER ';'", RedshiftCopySql.withoutTrailingTerminator("DELIMITER ';'"));
        assertEquals("gzip", RedshiftCopySql.withoutTrailingTerminator("gzip"));
    }

    @Test
    void separatorScanHandlesEscapedQuotes() {
        assertFalse(RedshiftCopySql.hasStatementSeparator("NULL AS 'it''s;ok'"));
        assertTrue(RedshiftCopySql.hasStatementSeparator("NULL AS 'it''s' ; DROP"));
    }

    @Test
    void doubleQuotedValueMayContainASemicolon() {
        assertFalse(RedshiftCopySql.hasStatementSeparator("DELIMITER \";\" CSV"));
    }

    @Test
    void escapeStringCannotHideASecondStatement() {
        assertTrue(RedshiftCopySql.hasStatementSeparator("NULL AS E'\\'';DROP TABLE t;--'"));
    }

    @Test
    void quoteInsideADoubleQuotedIdentifierCannotHideASecondStatement() {
        assertTrue(RedshiftCopySql.hasStatementSeparator("\"a'b\"; DROP TABLE t; --'"));
    }

    @Test
    void escapeStringKeepsAnEscapedQuoteInsideTheLiteral() {
        assertFalse(RedshiftCopySql.hasStatementSeparator("NULL AS E'it\\'s;ok'"));
    }

    @Test
    void dollarQuoteTagsWithDigitsOrNonAsciiLettersCannotHideASecondStatement() {
        assertTrue(RedshiftCopySql.hasStatementSeparator("NULL AS $a1$'$a1$; drop table t; --'"));
        assertTrue(RedshiftCopySql.hasStatementSeparator("NULL AS $é$'$é$;x;--'"));
        assertTrue(RedshiftCopySql.hasStatementSeparator("NULL AS $_tag9$ x $_tag9$"));
    }

    @Test
    void dollarQuoteTagOutsideTheBasicPlaneCannotHideASecondStatement() {
        assertTrue(RedshiftCopySql.hasStatementSeparator("NULL AS $😀$'$😀$; drop table t; --'"));
    }

    @Test
    void dollarSignInsideAQuotedLiteralIsAllowed() {
        assertFalse(RedshiftCopySql.hasStatementSeparator("NULL AS 'a$b$' CSV"));
        assertFalse(RedshiftCopySql.hasStatementSeparator("DELIMITER \"$\" CSV"));
    }

    @Test
    void anyDollarSignOutsideAQuotedLiteralIsRefused() {
        assertTrue(RedshiftCopySql.hasStatementSeparator("NULL AS $x"));
    }

    @Test
    void dollarQuotingAndCommentsAreRefused() {
        assertTrue(RedshiftCopySql.hasStatementSeparator("NULL AS $$'$$ gzip"));
        assertTrue(RedshiftCopySql.hasStatementSeparator("gzip -- trailing"));
        assertTrue(RedshiftCopySql.hasStatementSeparator("gzip /* x */"));
    }
}
