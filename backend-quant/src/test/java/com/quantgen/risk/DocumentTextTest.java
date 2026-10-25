package com.quantgen.risk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DocumentTextTest {

    @Test
    void masksNumbersPansAndEmailsButKeepsAmountsAndDates() {
        String masked = DocumentText.mask("A/c 123456789012, card 4111 1111 1111 1234, Aadhaar 1234 5678 9012, "
                + "phone 9876543210, PAN ABCDE1234F, mail asha.k@example.co.in. Net Rs. 1,23,456.00 on 2026-10-01.");
        assertThat(masked).contains("A/c ••••9012").contains("card ••••1234").contains("Aadhaar ••••9012")
                .contains("phone ••••3210").contains("PAN [PAN]").contains("mail [email]")
                .contains("Rs. 1,23,456.00").contains("2026-10-01");
    }

    @Test
    void aCitedValueMustBeOnThePage() {
        String page = "Gross pay: Rs. 45,000.00\nNet pay: 38500\nEmployer: Acme  Widgets Pvt Ltd";
        assertThat(DocumentText.appearsOn("₹38,500.00", page)).isTrue();
        assertThat(DocumentText.appearsOn("Rs. 45000/-", page)).isTrue();
        assertThat(DocumentText.appearsOn("acme widgets pvt ltd", page)).isTrue();
        assertThat(DocumentText.appearsOn("52,000", page)).isFalse();
        assertThat(DocumentText.appearsOn("Evil Corp", page)).isFalse();
        assertThat(DocumentText.appearsOn(" ", page)).isFalse();
    }
}
