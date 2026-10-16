package com.quantgen.common.web;

/** Validation patterns shared by request DTOs. */
public final class TextRules {

    /**
     * Free text that ends up in other people's screens, audit details or logs (notes, purposes, reasons):
     * no control characters, so it can't forge log lines or break layouts.
     */
    public static final String NO_CONTROL_CHARS = "^[^\\p{Cntrl}]*$";
    public static final String NO_CONTROL_CHARS_MESSAGE = "must not contain control characters";

    private TextRules() {
    }
}
