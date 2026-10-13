package com.celllock.app;

/** 用 JDK 直接运行，无需测试框架。 */
public final class InputValidatorTest {
    private static int cases;

    public static void main(String[] args) {
        ok(InputValidator.lteCell("1650", "323"));
        ok(InputValidator.lteCell("0", "0"));
        ok(InputValidator.lteCell("65535", "503"));
        bad(InputValidator.lteCell("65536", "3"));
        bad(InputValidator.lteCell("1650", "504"));
        bad(InputValidator.lteCell("-1", "3"));
        bad(InputValidator.lteCell("", "3"));
        bad(InputValidator.lteCell("99999999999", "3"));
        bad(InputValidator.lteCell("1650", "3;id"));
        bad(InputValidator.lteCell(null, "3"));
        bad(InputValidator.lteCell("1650", null));
        bad(InputValidator.lteCell("1.5", "3"));
        bad(InputValidator.lteCell("1e3", "3"));
        bad(InputValidator.lteCell("１６５０", "3"));
        bad(InputValidator.lteCell("1650", "-1"));
        ok(InputValidator.lteCell(" 1650 ", " 323 "));

        ok(InputValidator.nrCell("633984", "1007", "78", 30));
        ok(InputValidator.nrCell("3279165", "0", "257", 120));
        bad(InputValidator.nrCell("633984", "1008", "78", 30));
        bad(InputValidator.nrCell("3279166", "1", "78", 30));
        bad(InputValidator.nrCell("633984", "1", "", 30));
        bad(InputValidator.nrCell("633984", "1", "0", 30));
        bad(InputValidator.nrCell("633984", "1", "513", 30));
        bad(InputValidator.nrCell("633984", "1", "78", 25));
        bad(InputValidator.nrCell("633984", "1", null, 30));
        bad(InputValidator.nrCell("633984", null, "78", 30));
        bad(InputValidator.nrCell("633984", "1", "78;id", 30));
        ok(InputValidator.nrCell("0", "0", "1", 15));
        ok(InputValidator.nrCell("3279165", "1007", "512", 240));
        for (int scs : new int[]{15, 30, 60, 120, 240}) {
            ok(InputValidator.nrCell("633984", "202", "78", scs));
            ok(InputValidator.nrFrequency("504990", scs));
        }

        ok(InputValidator.nrFrequency("504990", 30));
        bad(InputValidator.nrFrequency("504990", 0));
        bad(InputValidator.nrFrequency("abc", 30));
        bad(InputValidator.nrFrequency(null, 30));
        bad(InputValidator.nrFrequency("3279166", 30));
        bad(InputValidator.nrFrequency("-1", 30));
        bad(InputValidator.nrFrequency("504990", -30));

        System.out.println("PASS: " + cases + " 项参数校验");
    }

    private static void ok(String error) {
        cases++;
        if (error != null) throw new AssertionError("误判为无效：" + error);
    }

    private static void bad(String error) {
        cases++;
        if (error == null) throw new AssertionError("误判为有效");
    }
}
