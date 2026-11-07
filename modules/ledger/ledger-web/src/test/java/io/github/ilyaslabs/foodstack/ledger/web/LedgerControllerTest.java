package io.github.ilyaslabs.foodstack.ledger.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.ledger.core.application.LedgerReports;
import io.github.ilyaslabs.foodstack.ledger.core.application.PayoutReport;
import io.github.ilyaslabs.foodstack.ledger.core.application.PayoutRow;
import io.github.ilyaslabs.foodstack.ledger.core.application.SettlementView;
import io.github.ilyaslabs.foodstack.ledger.core.application.StatementPage;
import io.github.ilyaslabs.foodstack.platform.security.ApiSecurityConfiguration;
import io.github.ilyaslabs.foodstack.platform.web.ProblemDetailsAdvice;
import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The ledger's REST endpoints with {@link LedgerReports} mocked: routes, downloads and refusals. */
@WebMvcTest(controllers = LedgerController.class, properties = "module-switch.modules.ledger.mode=local")
@Import({ ApiSecurityConfiguration.class, ProblemDetailsAdvice.class })
class LedgerControllerTest {

    @SpringBootApplication
    static class App {
    }

    @MockitoBean
    LedgerReports reports;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    MockMvc mvc;

    private static PayoutReport report() {
        PayoutRow row = new PayoutRow("store:s1", "STORE", "s1", "Napoli DHA", "b1", 0, Map.of("FOOD", 100L), 100, 0, 100,
                PayoutRow.OPERATOR_PAYS);
        return new PayoutReport("STORE", "2026-11-01", "2026-11-07", null, "PKR", List.of(row), Map.of("FOOD", 100L), 0, 100,
                100, 100, 0);
    }

    @Test
    @DisplayName("payout reports on screen, as CSV and as PDF downloads; signing in is required")
    void payouts() throws Exception {
        given(this.reports.payouts("STORE", "2026-11-01", "2026-11-07", null)).willReturn(report());
        given(this.reports.payoutsCsv("RIDER", "2026-11-01", "2026-11-07", null)).willReturn("Rider\r\n");
        given(this.reports.payoutsPdf("STORE", "2026-11-01", "2026-11-07", "b1")).willReturn(new byte[] { 37, 80, 68, 70 });
        this.mvc.perform(get("/api/v1/ledger/payouts?from=2026-11-01&to=2026-11-07")).andExpect(status().isUnauthorized());
        this.mvc.perform(get("/api/v1/ledger/payouts?from=2026-11-01&to=2026-11-07").with(jwt()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rows[0].name").value("Napoli DHA"))
            .andExpect(jsonPath("$.rows[0].direction").value("OPERATOR_PAYS"));
        this.mvc.perform(get("/api/v1/ledger/payouts.csv?party=RIDER&from=2026-11-01&to=2026-11-07").with(jwt()))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition", "attachment; filename=\"rider-payouts-2026-11-01-to-2026-11-07.csv\""))
            .andExpect(content().contentTypeCompatibleWith("text/csv"))
            .andExpect(content().string("Rider\r\n"));
        this.mvc.perform(get("/api/v1/ledger/payouts.pdf?from=2026-11-01&to=2026-11-07&brandId=b1").with(jwt()))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition", "attachment; filename=\"brand-payouts-2026-11-01-to-2026-11-07.pdf\""))
            .andExpect(content().contentType(MediaType.APPLICATION_PDF));
    }

    @Test
    @DisplayName("statements, settlements and settling; a refusal from the service is a 403")
    void settle() throws Exception {
        given(this.reports.statement("store:s1", null, null, null, 50))
            .willReturn(new StatementPage("store:s1", "Napoli", 10, "PKR", List.of(), null));
        this.mvc.perform(get("/api/v1/ledger/accounts/store:s1/statement").with(jwt()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balanceMinor").value(10));
        SettlementView view = new SettlementView("id", "store:s1", "STORE", "s1", "Napoli", "2026-11-07", 100, "PKR", "TRX-1",
                "f1", Instant.parse("2026-11-08T10:00:00Z"));
        given(this.reports.settle("store:s1", "2026-11-07", "TRX-1")).willReturn(view);
        this.mvc.perform(post("/api/v1/ledger/settlements").with(jwt()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"store:s1\",\"to\":\"2026-11-07\",\"reference\":\"TRX-1\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.reference").value("TRX-1"));
        verify(this.reports).settle("store:s1", "2026-11-07", "TRX-1");
        given(this.reports.settlements(null, 50))
            .willThrow(new ModuleException.Forbidden("Only finance sees the operator's accounts", "LEDGER_ACCESS_DENIED"));
        this.mvc.perform(get("/api/v1/ledger/settlements").with(jwt()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("LEDGER_ACCESS_DENIED"));
    }
}
