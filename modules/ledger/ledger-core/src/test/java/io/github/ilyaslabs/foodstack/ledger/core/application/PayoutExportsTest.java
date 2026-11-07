package io.github.ilyaslabs.foodstack.ledger.core.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.ledger.core.domain.Kinds;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PayoutExportsTest {

    private static PayoutReport report(int rows) {
        List<PayoutRow> list = new java.util.ArrayList<>();
        for (int i = 0; i < rows; i++) {
            Map<String, Long> kinds = new LinkedHashMap<>();
            kinds.put(Kinds.FOOD, 100_000L);
            kinds.put(Kinds.COMMISSION, -10_000L);
            list.add(new PayoutRow("store:s" + i, "STORE", "s" + i, (i == 0) ? "=Napoli, \"DHA\" کراچی" : "Branch " + i, "b1",
                    0, kinds, 90_000, 0, (i % 2 == 0) ? 90_000 : -2_000,
                    PayoutRow.direction((i % 2 == 0) ? 90_000 : -2_000)));
        }
        return new PayoutReport("STORE", "2026-11-01", "2026-11-07", "b1", "PKR", list,
                Map.of(Kinds.FOOD, 100_000L * rows, Kinds.COMMISSION, -10_000L * rows), 0, 90_000L * rows, 88_000, 90_000,
                2_000);
    }

    @Test
    @DisplayName("CSV: one row per branch, quoted where needed, formulas neutralised, amounts in major units")
    void csv() {
        String csv = PayoutExports.csv(report(2));
        String[] lines = csv.split("\r\n");
        assertThat(lines).hasSize(4);
        assertThat(lines[0]).isEqualTo(
                "Branch,Branch id,Brand id,Opening balance,Food,Commission,Closing balance,Net to settle,Who pays,Currency,From,To");
        assertThat(lines[1]).startsWith("\"'=Napoli, \"\"DHA\"\" کراچی\",s0,b1,0.00,1000.00,-100.00,900.00,900.00,Operator pays branch");
        assertThat(lines[2]).contains("20.00,Branch pays operator");
        assertThat(lines[3]).startsWith("Total,,b1,0.00,2000.00,-200.00,1800.00,880.00,Operator pays out (net)");
        assertThat(PayoutExports.major(-5, "JPY")).isEqualTo("-5");
        assertThat(PayoutExports.major(12_345, "KWD")).isEqualTo("12.345");
        assertThat(PayoutExports.cell("-12.50")).isEqualTo("-12.50");
    }

    @Test
    @DisplayName("PDF: pages of rows with the header repeated, totals, and names in other scripts never break it")
    void pdf() throws Exception {
        byte[] pdf = PayoutExports.pdf(report(60), "Lahore Eats");
        try (var document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isGreaterThan(1);
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("Branch payouts").contains("brand roll-up").contains("Lahore Eats")
                .contains("2026-11-01 to 2026-11-07").contains("Branch 59").contains("Total (60)").contains("Page 2");
        }
    }
}
