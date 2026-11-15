package io.github.ilyaslabs.foodstack.notification.web;

import io.github.ilyaslabs.evl.EVL;
import io.github.ilyaslabs.evl.Flow;
import java.util.List;

import io.github.ilyaslabs.foodstack.notification.core.application.TemplateEditor;
import io.github.ilyaslabs.foodstack.notification.core.application.TemplateEditor.Text;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The admin's message editor (PRD NO-2): every message in every enabled language. */
@RestController
@PreAuthorize("hasAuthority('settings:manage')")
class TemplatesController {

    private final TemplateEditor editor;

    TemplatesController(TemplateEditor editor) {
        this.editor = editor;
    }

    /**
     * A save: the new wording and the version it was based on ({@code 0} for a message not edited
     * yet).
     */
    record SaveRequest(String title, String body, String subject, Long version) {
    }

    record PreviewRequest(String title, String body, String subject) {
    }

    @GetMapping("/api/v1/notifications/templates")
    @Flow(value = "notification-templates", label = "List message templates")
    @EVL.Endpoint(method = "GET", path = "/api/v1/notifications/templates", caller = "Back office")
    List<TemplateEditor.Summary> list() {
        return this.editor.list();
    }

    @GetMapping("/api/v1/notifications/templates/{id}")
    @Flow(value = "notification-template-get", label = "Show template", description = "Built-in text and the operator's changes per language")
    @EVL.Endpoint(method = "GET", path = "/api/v1/notifications/templates/{id}", caller = "Back office")
    TemplateEditor.Details get(@PathVariable String id) {
        return this.editor.get(id);
    }

    @PutMapping("/api/v1/notifications/templates/{id}/{language}")
    @Flow(value = "notification-template-save", label = "Change template text", description = "Per language, with version check")
    @EVL.Endpoint(method = "PUT", path = "/api/v1/notifications/templates/{id}/{language}", caller = "Back office")
    TemplateEditor.LanguageText save(@PathVariable String id, @PathVariable String language,
            @RequestBody SaveRequest request) {
        return this.editor.save(id, language, new Text(request.title(), request.body(), request.subject()),
                request.version());
    }

    /** Goes back to the built-in text. */
    @DeleteMapping("/api/v1/notifications/templates/{id}/{language}")
    @Flow(value = "notification-template-reset", label = "Reset template to built-in")
    @EVL.Endpoint(method = "DELETE", path = "/api/v1/notifications/templates/{id}/{language}", caller = "Back office")
    TemplateEditor.LanguageText reset(@PathVariable String id, @PathVariable String language) {
        return this.editor.reset(id, language);
    }

    /** The email as a customer would get it, with sample values; nothing is saved. */
    @PostMapping("/api/v1/notifications/templates/{id}/{language}/preview")
    @Flow(value = "notification-template-preview", label = "Preview template")
    @EVL.Endpoint(method = "POST", path = "/api/v1/notifications/templates/{id}/{language}/preview", caller = "Back office")
    TemplateEditor.Preview preview(@PathVariable String id, @PathVariable String language,
            @RequestBody PreviewRequest request) {
        return this.editor.preview(id, language, new Text(request.title(), request.body(), request.subject()));
    }
}
