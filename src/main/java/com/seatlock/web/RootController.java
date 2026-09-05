package com.seatlock.web;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

/**
 * Sends the root path to the demo page.
 *
 * <p>Without this, {@code GET /} returns a bare {@code 401 UNAUTHENTICATED} —
 * technically correct, since the root is not a defined endpoint and this is an
 * API rather than a website, but useless to a human who has been handed the URL
 * and typed it into a browser. They conclude the service is broken.
 *
 * <p>It points at {@code /demo/} rather than Swagger deliberately. Swagger
 * answers "what endpoints exist"; the demo answers "what does this system
 * actually guarantee", which is the more interesting question and the one a
 * visitor is more likely to have. The demo links onward to the docs.
 *
 * <p>Hidden from the OpenAPI document: a convenience for people, not part of
 * the API contract.
 */
@RestController
@Hidden
public class RootController {

    @GetMapping("/")
    public RedirectView root() {
        return new RedirectView("/demo/index.html");
    }

    /**
     * Spring Boot's welcome-page handling resolves {@code index.html} for the
     * application root only. A directory-style request one level down finds no
     * handler and no static resource, so it has to be mapped explicitly.
     */
    @GetMapping("/demo/")
    public RedirectView demo() {
        return new RedirectView("/demo/index.html");
    }
}
