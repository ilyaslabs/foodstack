package io.github.ilyaslabs.foodstack.ledger.web;

import io.github.ilyaslabs.evl.EVL;
import io.github.ilyaslabs.evl.Flow;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import io.github.ilyaslabs.foodstack.ledger.core.application.LedgerReports;
import io.github.ilyaslabs.foodstack.ledger.core.application.OperatorSummary;
import io.github.ilyaslabs.foodstack.ledger.core.application.PayoutReport;
import io.github.ilyaslabs.foodstack.ledger.core.application.SettlementView;
import io.github.ilyaslabs.foodstack.ledger.core.application.StatementPage;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The ledger for the back office (PRD FI-1, FI-3, FI-7, BB-8): payout reports on screen, as CSV
 * and as PDF, the operator's earnings, statements, and settling a balance. Who may see what is
 * checked by {@link LedgerReports}.
 */
@RestController
@RequestMapping("/api/v1/ledger")
class LedgerController {

    private final LedgerReports reports;

    LedgerController(LedgerReports reports) {
        this.reports = reports;
    }

    /**
     * @param accountId the branch's or rider's account
     * @param to the last day settled
     * @param reference bank or payment reference
     */
    record SettleRequest(String accountId, String to, String reference) {
    }

    @GetMapping("/payouts")
    @PreAuthorize("isAuthenticated()")
    @Flow(value = "ledger-payouts", label = "Payout report", description = "What each branch, brand or rider is owed or owes for a period, from daily rollups plus fresh postings")
    @EVL.Endpoint(method = "GET", path = "/api/v1/ledger/payouts", caller = "Back office")
    PayoutReport payouts(@RequestParam(defaultValue = "STORE") String party, @RequestParam String from,
            @RequestParam String to, @RequestParam(required = false) String brandId) {
        return this.reports.payouts(party, from, to, brandId);
    }

    @GetMapping("/payouts.csv")
    @PreAuthorize("isAuthenticated()")
    @Flow(value = "ledger-payouts-csv", label = "Payout report as CSV")
    @EVL.Endpoint(method = "GET", path = "/api/v1/ledger/payouts.csv", caller = "Back office")
    ResponseEntity<byte[]> payoutsCsv(@RequestParam(defaultValue = "STORE") String party, @RequestParam String from,
            @RequestParam String to, @RequestParam(required = false) String brandId) {
        // @flow ledger-payouts-csv>report: Build the payout report and write it as CSV
        byte[] body = this.reports.payoutsCsv(party, from, to, brandId).getBytes(StandardCharsets.UTF_8);
        return download(body, new MediaType("text", "csv", StandardCharsets.UTF_8), name(party, from, to, brandId, "csv"));
    }

    @GetMapping("/payouts.pdf")
    @PreAuthorize("isAuthenticated()")
    @Flow(value = "ledger-payouts-pdf", label = "Payout report as PDF", description = "With the operator's name on top")
    @EVL.Endpoint(method = "GET", path = "/api/v1/ledger/payouts.pdf", caller = "Back office")
    ResponseEntity<byte[]> payoutsPdf(@RequestParam(defaultValue = "STORE") String party, @RequestParam String from,
            @RequestParam String to, @RequestParam(required = false) String brandId) {
        // @flow ledger-payouts-pdf>report: Build the payout report and render it as PDF
        return download(this.reports.payoutsPdf(party, from, to, brandId), MediaType.APPLICATION_PDF,
                name(party, from, to, brandId, "pdf"));
    }

    @GetMapping("/operator")
    @PreAuthorize("isAuthenticated()")
    @Flow(value = "ledger-operator-summary", label = "Operator summary", description = "What the operator earned and spent in a period; finance only")
    @EVL.Endpoint(method = "GET", path = "/api/v1/ledger/operator", caller = "Back office")
    OperatorSummary operator(@RequestParam String from, @RequestParam String to) {
        return this.reports.operator(from, to);
    }

    @GetMapping("/accounts/{accountId}/statement")
    @PreAuthorize("isAuthenticated()")
    @Flow(value = "ledger-statement", label = "Account statement", description = "Postings of one account, newest first")
    @EVL.Endpoint(method = "GET", path = "/api/v1/ledger/accounts/{accountId}/statement", caller = "Back office")
    StatementPage statement(@PathVariable String accountId, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String before,
            @RequestParam(defaultValue = "50") int limit) {
        return this.reports.statement(accountId, from, to, before, limit);
    }

    @GetMapping("/settlements")
    @PreAuthorize("isAuthenticated()")
    @Flow(value = "ledger-settlements", label = "List settlements")
    @EVL.Endpoint(method = "GET", path = "/api/v1/ledger/settlements", caller = "Back office")
    List<SettlementView> settlements(@RequestParam(required = false) String accountId,
            @RequestParam(defaultValue = "50") int limit) {
        return this.reports.settlements(accountId, limit);
    }

    @PostMapping("/settlements")
    @PreAuthorize("isAuthenticated()")
    @Flow(value = "ledger-settle", label = "Settle a balance", description = "Finance records a bank payment to or from a branch or rider")
    @EVL.Endpoint(method = "POST", path = "/api/v1/ledger/settlements", caller = "Back office")
    SettlementView settle(@RequestBody SettleRequest body) {
        return this.reports.settle(body.accountId(), body.to(), body.reference());
    }

    private static ResponseEntity<byte[]> download(byte[] body, MediaType type, String filename) {
        return ResponseEntity.ok()
            .contentType(type)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(body);
    }

    private static String name(String party, String from, String to, String brandId, String extension) {
        String who = "RIDER".equalsIgnoreCase(party) ? "rider" : (brandId != null && !brandId.isBlank()) ? "brand" : "branch";
        return (who + "-payouts-" + from + "-to-" + to + "." + extension).replaceAll("[^A-Za-z0-9._-]", "")
            .toLowerCase(Locale.ROOT);
    }
}
