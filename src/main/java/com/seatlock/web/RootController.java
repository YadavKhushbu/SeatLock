package com.seatlock.web;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

/**
 * Sends the root path to the API documentation.
 *
 * <p>Without this, {@code GET /} returns a bare {@code 401 UNAUTHENTICATED} —
 * technically correct, since the root is not a defined endpoint and this is an
 * API rather than a website, but useless to a human who has been handed the URL
 * and simply typed it into a browser. They conclude the service is broken.
 *
 * <p>The redirect costs nothing and removes that failure of hospitality. It is
 * hidden from the OpenAPI document because it is a convenience for people, not
 * part of the API contract.
 */
@RestController
@Hidden
public class RootController {

    @GetMapping("/")
    public RedirectView root() {
        return new RedirectView("/swagger-ui.html");
    }
}
