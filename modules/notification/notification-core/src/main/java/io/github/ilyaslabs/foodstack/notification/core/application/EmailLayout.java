package io.github.ilyaslabs.foodstack.notification.core.application;

import org.springframework.web.util.HtmlUtils;

/**
 * The email around a message: the operator's name and colour, a heading, the text and an optional
 * button, right to left for Arabic and Urdu. Plain tables and inline styles, as email clients need.
 */
final class EmailLayout {

    private EmailLayout() {
    }

    static String html(String brand, String color, String language, String title, String body, String buttonText,
            String buttonUrl) {
        boolean rtl = Templates.rightToLeft(language);
        String accent = (color != null && color.matches("#[0-9a-fA-F]{6}")) ? color : "#6750A4";
        String align = rtl ? "right" : "left";
        String button = (buttonUrl == null) ? "" : """
                <tr><td style="padding:8px 32px 32px"><a href="%s" style="display:inline-block;background:%s;color:#ffffff;\
                text-decoration:none;font-weight:500;padding:12px 24px;border-radius:20px">%s</a></td></tr>"""
            .formatted(HtmlUtils.htmlEscape(buttonUrl), accent, HtmlUtils.htmlEscape(buttonText));
        return """
                <!doctype html><html lang="%s" dir="%s"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1"><title>%s</title></head>
                <body style="margin:0;background:#f4f1f6;font-family:Roboto,Arial,sans-serif;color:#1d1b20">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:#f4f1f6;padding:24px 0">
                <tr><td align="center">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="max-width:560px;background:#ffffff;\
                border-radius:16px;text-align:%s">
                <tr><td style="padding:24px 32px 0;color:%s;font-size:16px;font-weight:700">%s</td></tr>
                <tr><td style="padding:16px 32px 0;font-size:22px;font-weight:500;line-height:1.3">%s</td></tr>
                <tr><td style="padding:12px 32px 24px;font-size:15px;line-height:1.6;color:#49454f">%s</td></tr>
                %s
                </table>
                <p style="font-size:12px;color:#79747e;margin:16px">%s</p>
                </td></tr></table></body></html>"""
            .formatted(HtmlUtils.htmlEscape(language), rtl ? "rtl" : "ltr", HtmlUtils.htmlEscape(title), align, accent,
                    HtmlUtils.htmlEscape(brand), HtmlUtils.htmlEscape(title), HtmlUtils.htmlEscape(body), button,
                    HtmlUtils.htmlEscape(brand));
    }

    static String text(String brand, String title, String body, String buttonUrl) {
        return title + "\n\n" + body + ((buttonUrl != null) ? "\n\n" + buttonUrl : "") + "\n\n" + brand + "\n";
    }
}
