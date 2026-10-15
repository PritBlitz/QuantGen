package com.quantgen.common.money;

/** Display helpers for account numbers. Full numbers never go into logs or audit details. */
public final class AccountNumbers {

    private AccountNumbers() {
    }

    /** "123456789012" → "••••9012". */
    public static String mask(String accountNumber) {
        if (accountNumber == null || accountNumber.length() < 4) {
            return "••••";
        }
        return "••••" + accountNumber.substring(accountNumber.length() - 4);
    }
}
