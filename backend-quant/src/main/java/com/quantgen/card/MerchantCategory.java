package com.quantgen.card;

/** Merchant categories of the sandbox checkout (PRD 12.11), and the spending category each maps to (12.5). */
public enum MerchantCategory {
    GROCERY("GROCERIES"),
    DINING("FOOD"),
    TRAVEL("TRAVEL"),
    ELECTRONICS("SHOPPING"),
    GAMBLING("ENTERTAINMENT"),
    FUEL("TRANSPORT"),
    ENTERTAINMENT("ENTERTAINMENT"),
    OTHER("SHOPPING");

    private final String spendingCategory;

    MerchantCategory(String spendingCategory) {
        this.spendingCategory = spendingCategory;
    }

    public String spendingCategory() {
        return spendingCategory;
    }
}
