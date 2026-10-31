package org.aristonis.diwan.database.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class SqliteScriptsTest {

    @Test
    void splitsAtSemicolonsOutsideStringsNamesAndComments() {
        assertEquals(Arrays.asList("create table t (a text)", "insert into t values ('x;y')",
                "insert into \"t;\" values (1)", "select 1 -- a; comment", "/* ; */ select 2"),
                SqliteScripts.split("create table t (a text); insert into t values ('x;y');"
                        + " insert into \"t;\" values (1); select 1 -- a; comment\n; /* ; */ select 2;"));
    }

    @Test
    void keepsATriggerBodyWhole() {
        String trigger = "create temp trigger t after insert on a begin insert into b values (1);"
                + " update c set n = n + 1; end";

        assertEquals(Arrays.asList(trigger, "select 1"), SqliteScripts.split(trigger + "; select 1;"));
    }

    @Test
    void aCaseInsideATriggerBodyDoesNotEndIt() {
        String trigger = "create trigger t after insert on a begin update b set x = case when new.y then 1"
                + " else 2 end; insert into c values (1); end";

        assertEquals(Arrays.asList(trigger, "select 1"), SqliteScripts.split(trigger + "; select 1"));
    }

    @Test
    void aTrailingCommentIsNotAStatement() {
        assertEquals(Arrays.asList("select 1"), SqliteScripts.split("select 1; -- tail\n /* more */"));
    }

    @Test
    void dropsEmptyStatements() {
        assertEquals(Arrays.asList("select 1"), SqliteScripts.split(" ;; select 1 ; ; "));
    }
}
