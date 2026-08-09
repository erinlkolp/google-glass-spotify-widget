package dev.erinlkolp.glassspotify.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

/** Scripted HttpTransport. Every core test drives this instead of a network. */
public final class FakeHttpTransport implements HttpTransport {

    private final Queue<HttpResponse> responses = new LinkedList<HttpResponse>();
    private final List<String> methods = new ArrayList<String>();
    private final List<String> urls = new ArrayList<String>();
    private final List<String> bearers = new ArrayList<String>();
    private final List<String> bodies = new ArrayList<String>();
    private IOException failure;

    public void enqueue(int code, String body) {
        responses.add(new HttpResponse(code, body));
    }

    /** Makes every subsequent call throw, simulating a dead network. */
    public void failWith(IOException e) {
        this.failure = e;
    }

    public List<String> methods() {
        return methods;
    }

    public List<String> urls() {
        return urls;
    }

    public List<String> bearers() {
        return bearers;
    }

    public List<String> bodies() {
        return bodies;
    }

    @Override
    public HttpResponse execute(String method, String url, String bearer, String formBody)
            throws IOException {
        methods.add(method);
        urls.add(url);
        bearers.add(bearer);
        bodies.add(formBody);
        if (failure != null) {
            throw failure;
        }
        if (responses.isEmpty()) {
            throw new IllegalStateException("no response enqueued for " + method + " " + url);
        }
        return responses.remove();
    }
}
