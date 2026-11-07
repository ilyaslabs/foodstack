package io.github.ilyaslabs.foodstack.ledger.core.application;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.ilyaslabs.foodstack.ledger.core.domain.Accounts;
import io.github.ilyaslabs.foodstack.ledger.core.domain.Kinds;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;

/**
 * Payout reports as CSV and PDF (PRD FI-3): one row per branch or rider, the period's movements by
 * kind, the closing balance and the net amount with who pays whom, then the totals. Amounts are in
 * major units with the currency's decimals, so the CSV opens in a spreadsheet as numbers.
 */
public final class PayoutExports {

    private static final Map<String, String> LABELS = Map.ofEntries(Map.entry(Kinds.FOOD, "Food"),
            Map.entry(Kinds.TAX, "Tax"), Map.entry(Kinds.DELIVERY, "Delivery"), Map.entry(Kinds.TIP, "Tips"),
            Map.entry(Kinds.DISCOUNT, "Discounts"), Map.entry(Kinds.COMMISSION, "Commission"),
            Map.entry(Kinds.SERVICE_FEE, "Service fee"), Map.entry(Kinds.REFUND, "Refunds"),
            Map.entry(Kinds.CASH, "Cash held"), Map.entry(Kinds.COLLECTED, "Collected directly"),
            Map.entry(Kinds.EARNING, "Delivery pay"), Map.entry(Kinds.SETTLEMENT, "Settled"),
            Map.entry(Kinds.PAYMENT, "Payments"));

    private PayoutExports() {
    }

    /** The label of a kind, as on the back office. */
    public static String label(String kind) {
        return LABELS.getOrDefault(kind, kind);
    }

    /** The kinds that appear in the report, in report order. */
    static List<String> columns(PayoutReport report) {
        Set<String> present = new LinkedHashSet<>();
        for (String kind : Kinds.REPORTED) {
            if (report.totalKinds().containsKey(kind)
                    || report.rows().stream().anyMatch((r) -> r.kinds().containsKey(kind))) {
                present.add(kind);
            }
        }
        report.totalKinds().keySet().forEach(present::add);
        return new ArrayList<>(present);
    }

    // ---- CSV ----------------------------------------------------------------------------------

    public static String csv(PayoutReport report) {
        List<String> kinds = columns(report);
        boolean stores = Accounts.STORE.equals(report.partyType());
        StringBuilder out = new StringBuilder();
        List<String> header = new ArrayList<>(List.of(stores ? "Branch" : "Rider", stores ? "Branch id" : "Rider id"));
        if (stores) {
            header.add("Brand id");
        }
        header.add("Opening balance");
        kinds.forEach((k) -> header.add(label(k)));
        header.addAll(List.of("Closing balance", "Net to settle", "Who pays", "Currency", "From", "To"));
        line(out, header);
        for (PayoutRow row : report.rows()) {
            List<String> cells = new ArrayList<>(List.of(row.name(), row.partyId()));
            if (stores) {
                cells.add((row.brandId() != null) ? row.brandId() : "");
            }
            cells.add(major(row.openingMinor(), report.currency()));
            kinds.forEach((k) -> cells.add(major(row.kinds().getOrDefault(k, 0L), report.currency())));
            cells.addAll(List.of(major(row.closingMinor(), report.currency()), major(Math.abs(row.dueMinor()), report.currency()),
                    whoPays(row, stores), report.currency(), report.from(), report.to()));
            line(out, cells);
        }
        List<String> totals = new ArrayList<>(List.of("Total", ""));
        if (stores) {
            totals.add(report.brandId() != null ? report.brandId() : "");
        }
        totals.add(major(report.totalOpeningMinor(), report.currency()));
        kinds.forEach((k) -> totals.add(major(report.totalKinds().getOrDefault(k, 0L), report.currency())));
        totals.addAll(List.of(major(report.totalClosingMinor(), report.currency()),
                major(Math.abs(report.totalDueMinor()), report.currency()),
                report.totalDueMinor() > 0 ? "Operator pays out (net)" : report.totalDueMinor() < 0 ? "Operator receives (net)" : "",
                report.currency(), report.from(), report.to()));
        line(out, totals);
        return out.toString();
    }

    private static void line(StringBuilder out, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(cell(cells.get(i)));
        }
        out.append("\r\n");
    }

    /** RFC 4180 quoting; a leading formula character is neutralised so a spreadsheet never runs it. */
    static String cell(String value) {
        String v = (value != null) ? value : "";
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0 && !v.matches("-?\\d+(\\.\\d+)?")) {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    private static String whoPays(PayoutRow row, boolean stores) {
        String party = stores ? "Branch" : "Rider";
        return switch (row.direction()) {
            case PayoutRow.OPERATOR_PAYS -> "Operator pays " + party.toLowerCase(java.util.Locale.ROOT);
            case PayoutRow.PARTY_PAYS -> party + " pays operator";
            default -> "Settled";
        };
    }

    /** Minor units as a plain decimal in major units, such as {@code -1250.50}. */
    static String major(long minor, String currency) {
        int digits = fractionDigits(currency);
        return BigDecimal.valueOf(minor, digits).toPlainString();
    }

    private static int fractionDigits(String currency) {
        try {
            int digits = Currency.getInstance(currency).getDefaultFractionDigits();
            return Math.max(digits, 0);
        }
        catch (RuntimeException ex) {
            return 2;
        }
    }

    // ---- PDF ----------------------------------------------------------------------------------

    /** A landscape A4 report with the operator's name on top. */
    public static byte[] pdf(PayoutReport report, String operatorName) {
        try (PDDocument document = new PDDocument()) {
            PDType0Font regular = font(document, "fonts/Roboto-Regular.ttf");
            PDType0Font medium = font(document, "fonts/Roboto-Medium.ttf");
            new PdfWriter(document, regular, medium, report, operatorName).write();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static PDType0Font font(PDDocument document, String resource) throws IOException {
        try (InputStream in = PayoutExports.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Missing font " + resource);
            }
            return PDType0Font.load(document, in);
        }
    }

    /** Lays out the report page by page. */
    private static final class PdfWriter {

        private static final float MARGIN = 36;

        private static final float ROW = 16;

        private static final PDColor TEXT = rgb(0x1c, 0x1b, 0x1f);

        private static final PDColor MUTED = rgb(0x5f, 0x5e, 0x66);

        private static final PDColor RULE = rgb(0xd6, 0xd4, 0xdc);

        private static final PDColor BAND = rgb(0xf3, 0xf1, 0xf7);

        private final PDDocument document;

        private final PDType0Font regular;

        private final PDType0Font medium;

        private final PayoutReport report;

        private final String operatorName;

        private final List<String> kinds;

        private final boolean stores;

        private final float[] widths;

        /** Smaller when many kinds share the page width. */
        private final float size;

        private PDPageContentStream content;

        private float y;

        private int page;

        PdfWriter(PDDocument document, PDType0Font regular, PDType0Font medium, PayoutReport report, String operatorName) {
            this.document = document;
            this.regular = regular;
            this.medium = medium;
            this.report = report;
            this.operatorName = (operatorName != null && !operatorName.isBlank()) ? operatorName : "FoodStack";
            this.kinds = columns(report);
            this.stores = Accounts.STORE.equals(report.partyType());
            float usable = PDRectangle.A4.getHeight() - 2 * MARGIN;
            int amountColumns = this.kinds.size() + 3;
            float nameWidth = Math.max(110, usable - amountColumns * 62 - 70);
            float amountWidth = (usable - nameWidth - 70) / amountColumns;
            this.widths = new float[amountColumns + 2];
            this.widths[0] = nameWidth;
            for (int i = 1; i <= amountColumns; i++) {
                this.widths[i] = amountWidth;
            }
            this.widths[amountColumns + 1] = 70;
            this.size = (amountWidth < 50) ? 7f : 8.5f;
        }

        void write() throws IOException {
            newPage();
            for (int i = 0; i < this.report.rows().size(); i++) {
                PayoutRow row = this.report.rows().get(i);
                if (this.y < MARGIN + 2 * ROW) {
                    newPage();
                }
                if (i % 2 == 1) {
                    band();
                }
                List<String> cells = new ArrayList<>();
                cells.add(row.name());
                cells.add(major(row.openingMinor(), this.report.currency()));
                this.kinds.forEach((k) -> cells.add(major(row.kinds().getOrDefault(k, 0L), this.report.currency())));
                cells.add(major(row.closingMinor(), this.report.currency()));
                cells.add(major(Math.abs(row.dueMinor()), this.report.currency()));
                cells.add(switch (row.direction()) {
                    case PayoutRow.OPERATOR_PAYS -> "We pay";
                    case PayoutRow.PARTY_PAYS -> "They pay";
                    default -> "Settled";
                });
                rowText(cells, this.regular, TEXT);
            }
            if (this.y < MARGIN + 3 * ROW) {
                newPage();
            }
            rule(this.y + ROW - 4);
            List<String> totals = new ArrayList<>();
            totals.add("Total (" + this.report.rows().size() + ")");
            totals.add(major(this.report.totalOpeningMinor(), this.report.currency()));
            this.kinds.forEach((k) -> totals.add(major(this.report.totalKinds().getOrDefault(k, 0L), this.report.currency())));
            totals.add(major(this.report.totalClosingMinor(), this.report.currency()));
            totals.add(major(Math.abs(this.report.totalDueMinor()), this.report.currency()));
            totals.add(this.report.totalDueMinor() > 0 ? "We pay" : this.report.totalDueMinor() < 0 ? "We receive" : "");
            rowText(totals, this.medium, TEXT);
            this.y -= ROW / 2;
            text(this.regular, 9, MUTED, MARGIN, this.y,
                    "We pay out " + major(this.report.operatorPaysMinor(), this.report.currency()) + " and receive "
                            + major(this.report.partiesPayMinor(), this.report.currency()) + " " + this.report.currency()
                            + ". Positive balances are owed by the operator; negative balances are owed to it.");
            this.content.close();
        }

        private void newPage() throws IOException {
            if (this.content != null) {
                this.content.close();
            }
            PDPage pdPage = new PDPage(new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth()));
            this.document.addPage(pdPage);
            this.page++;
            this.content = new PDPageContentStream(this.document, pdPage);
            float top = pdPage.getMediaBox().getHeight() - MARGIN;
            text(this.medium, 15, TEXT, MARGIN, top - 4,
                    (this.stores ? "Branch payouts" : "Rider payouts") + (this.report.brandId() != null ? " — brand roll-up" : ""));
            text(this.regular, 9, MUTED, MARGIN, top - 20, this.operatorName + " · " + this.report.from() + " to "
                    + this.report.to() + " · amounts in " + this.report.currency());
            text(this.regular, 9, MUTED, pdPage.getMediaBox().getWidth() - MARGIN - 40, top - 4, "Page " + this.page);
            this.y = top - 48;
            List<String> header = new ArrayList<>();
            header.add(this.stores ? "Branch" : "Rider");
            header.add("Opening");
            this.kinds.forEach((k) -> header.add(label(k)));
            header.add("Closing");
            header.add("Net");
            header.add("Who pays");
            rowText(header, this.medium, MUTED);
            rule(this.y + ROW - 4);
        }

        private void rowText(List<String> cells, PDType0Font font, PDColor color) throws IOException {
            float x = MARGIN;
            for (int i = 0; i < cells.size(); i++) {
                String cell = fit(font, this.size, safe(font, cells.get(i)), this.widths[i] - 6);
                boolean amount = i > 0 && i < cells.size() - 1;
                float textX = amount ? x + this.widths[i] - 6 - width(font, this.size, cell) : x;
                text(font, this.size, color, textX, this.y, cell);
                x += this.widths[i];
            }
            this.y -= ROW;
        }

        private void band() throws IOException {
            this.content.setNonStrokingColor(BAND);
            this.content.addRect(MARGIN - 4, this.y - 4, sum(this.widths) + 4, ROW);
            this.content.fill();
        }

        private void rule(float at) throws IOException {
            this.content.setStrokingColor(RULE);
            this.content.setLineWidth(0.6f);
            this.content.moveTo(MARGIN - 4, at);
            this.content.lineTo(MARGIN + sum(this.widths), at);
            this.content.stroke();
        }

        private void text(PDType0Font font, float size, PDColor color, float x, float atY, String value) throws IOException {
            this.content.beginText();
            this.content.setNonStrokingColor(color);
            this.content.setFont(font, size);
            this.content.newLineAtOffset(x, atY);
            this.content.showText(safe(font, value));
            this.content.endText();
        }

        private static float sum(float[] values) {
            float total = 0;
            for (float v : values) {
                total += v;
            }
            return total;
        }

        private static float width(PDType0Font font, float size, String value) throws IOException {
            return font.getStringWidth(value) / 1000 * size;
        }

        /** Shortens a cell with an ellipsis to fit its column. */
        private static String fit(PDType0Font font, float size, String value, float max) throws IOException {
            if (width(font, size, value) <= max) {
                return value;
            }
            String cut = value;
            while (cut.length() > 1 && width(font, size, cut + "…") > max) {
                cut = cut.substring(0, cut.length() - 1);
            }
            return cut + "…";
        }

        /** Characters the font has no glyph for become {@code ?}, so a name in another script never breaks the report. */
        private static String safe(PDType0Font font, String value) {
            if (value == null) {
                return "";
            }
            StringBuilder out = new StringBuilder(value.length());
            value.codePoints().forEach((cp) -> {
                if (cp < 0x20) {
                    out.append(' ');
                    return;
                }
                String one = new String(Character.toChars(cp));
                try {
                    font.encode(one);
                    out.append(one);
                }
                catch (IOException | IllegalArgumentException ex) {
                    out.append('?');
                }
            });
            return out.toString();
        }

        private static PDColor rgb(int r, int g, int b) {
            return new PDColor(new float[] { r / 255f, g / 255f, b / 255f }, PDDeviceRGB.INSTANCE);
        }
    }
}
