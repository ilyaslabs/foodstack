package io.github.ilyaslabs.foodstack.payment.web;

import java.math.BigDecimal;
import java.net.URI;
import java.util.Currency;
import java.util.Map;

import io.github.ilyaslabs.foodstack.payment.core.application.SandboxCheckout.Page;
import io.github.ilyaslabs.foodstack.payment.core.application.SandboxCheckout;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/**
 * The sandbox gateway's payment page: only when {@code PAYMENT_GATEWAY=sandbox} (never in
 * production). The page token in the URL proves the server handed it out.
 */
@RestController
class SandboxPageController {

    private final SandboxCheckout sandbox;

    SandboxPageController(SandboxCheckout sandbox) {
        this.sandbox = sandbox;
    }

    @GetMapping(value = "/api/v1/payments/sandbox/{orderId}", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> page(@PathVariable String orderId, @RequestParam("t") String token) {
        Page page = this.sandbox.page(orderId, token);
        String amount = format(page.amountMinor(), page.currency());
        String id = HtmlUtils.htmlEscape(orderId);
        String t = HtmlUtils.htmlEscape(token);
        boolean open = "PENDING".equals(page.status());
        StringBuilder saved = new StringBuilder();
        for (var card : page.savedCards()) {
            saved.append("<p class=\"saved\">Saved: ").append(HtmlUtils.htmlEscape(card.brand())).append(" ending ")
                .append(HtmlUtils.htmlEscape(card.last4())).append("</p>");
        }
        String keep = page.canSaveCard()
                ? "<label class=\"keep\"><input type=\"checkbox\" name=\"save\" value=\"true\"> Save this card for next time</label>"
                : "";
        String actions = open ? """
                %4$s<form method="post" action="/api/v1/payments/sandbox/%1$s?t=%2$s">
                  %5$s
                  <button name="outcome" value="pay" class="pay">Pay %3$s</button>
                  <button name="outcome" value="decline" class="decline">Decline</button>
                </form>""".formatted(id, t, amount, saved, keep)
                : "<p class=\"status\">This payment is " + HtmlUtils.htmlEscape(page.status().toLowerCase()) + ".</p>";
        String html = """
                <!doctype html><html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Sandbox payment</title>
                <style>
                  :root { color-scheme: light dark; font-family: Roboto, system-ui, sans-serif; }
                  body { margin: 0; min-height: 100vh; display: grid; place-items: center; background: #f4f1f6; color: #1d1b20; }
                  main { width: min(420px, calc(100%% - 32px)); background: #fff; border-radius: 24px; padding: 32px;
                         box-shadow: 0 2px 12px rgb(0 0 0 / 12%%); }
                  .badge { display: inline-block; font-size: 12px; font-weight: 500; letter-spacing: .5px; padding: 4px 10px;
                           border-radius: 8px; background: #ffe08a; color: #3d2f00; }
                  h1 { font-size: 22px; font-weight: 500; margin: 16px 0 4px; }
                  .amount { font-size: 36px; font-weight: 500; margin: 16px 0 24px; }
                  p { color: #49454f; line-height: 1.5; }
                  form { display: grid; gap: 12px; }
                  button { font: inherit; font-weight: 500; font-size: 15px; height: 48px; border-radius: 24px; border: 0;
                           cursor: pointer; }
                  .pay { background: #6750a4; color: #fff; }
                  .keep { display: flex; gap: 8px; align-items: center; color: #49454f; }
                  .saved { margin: 0 0 12px; font-weight: 500; }
                  .decline { background: transparent; color: #6750a4; border: 1px solid #79747e; }
                  @media (prefers-color-scheme: dark) {
                    body { background: #141218; color: #e6e0e9; } main { background: #211f26; } p { color: #cac4d0; }
                    .pay { background: #d0bcff; color: #381e72; } .decline { color: #d0bcff; border-color: #938f99; }
                  }
                </style></head><body><main>
                <span class="badge">TEST MODE</span>
                <h1>Sandbox payment</h1>
                <p>No real card is charged. Choose what the card gateway should answer.</p>
                <div class="amount">%s</div>
                %s
                </main></body></html>""".formatted(HtmlUtils.htmlEscape(amount), actions);
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(html);
    }

    @PostMapping("/api/v1/payments/sandbox/{orderId}")
    ResponseEntity<Void> complete(@PathVariable String orderId, @RequestParam("t") String token,
            @RequestParam("outcome") String outcome, @RequestParam(name = "save", defaultValue = "false") boolean save) {
        String next = this.sandbox.complete(orderId, token, "pay".equals(outcome), save);
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(next)).build();
    }

    /** The body of the app's pretend embedded card fields. */
    record CardPayment(String clientSecret, String cardNumber, String savedCardId, boolean saveCard) {
    }

    /**
     * Completes a sandbox payment from the app's pretend embedded fields (PRD CO-5); the client
     * secret proves the server handed this payment to the customer.
     */
    @PostMapping("/api/v1/payments/sandbox/{orderId}/card")
    Map<String, String> card(@PathVariable String orderId, @RequestBody CardPayment body) {
        return Map.of("next", this.sandbox.completeEmbedded(orderId, body.clientSecret(), body.cardNumber(),
                body.savedCardId(), body.saveCard()));
    }

    static String format(long minor, String currency) {
        int digits = Math.max(Currency.getInstance(currency).getDefaultFractionDigits(), 0);
        return currency + " " + BigDecimal.valueOf(minor, digits).toPlainString();
    }
}
