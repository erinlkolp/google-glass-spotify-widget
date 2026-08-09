package dev.erinlkolp.glassspotify.core;

import java.io.IOException;

/**
 * The single network seam in this project.
 *
 * <p>Every class above this interface is unit-tested against a fake. Only
 * {@link UrlHttpTransport} performs real I/O.
 */
public interface HttpTransport {

    /**
     * @param method  HTTP verb, e.g. "GET", "PUT", "POST"
     * @param url     absolute https URL
     * @param bearer  OAuth access token, or null to send no Authorization header
     * @param formBody application/x-www-form-urlencoded body, or null to send none
     */
    HttpResponse execute(String method, String url, String bearer, String formBody)
            throws IOException;
}
