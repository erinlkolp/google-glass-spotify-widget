package dev.erinlkolp.glassspotify.core;

/** A completed HTTP exchange. */
public final class HttpResponse {

    public final int code;
    /** Response body, or the error body for a 4xx/5xx. Never null; may be empty. */
    public final String body;

    public HttpResponse(int code, String body) {
        this.code = code;
        this.body = body == null ? "" : body;
    }
}
