package mx.surtido.messaging;

import java.util.HashMap;
import java.util.Map;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Carries the trace across the outbox. The relay sends events from a
 * scheduled job with no request around it; without this, every event would
 * start a new trace and the saga would show up as unconnected fragments.
 */
@Component
public class TraceCarrier {
    private static final String HEADER = "traceparent";

    private final ObjectProvider<Tracer> tracer;
    private final ObjectProvider<Propagator> propagator;

    public TraceCarrier(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /** The current trace context as a W3C traceparent, or null when nothing is being traced. */
    public String capture() {
        Tracer t = tracer.getIfAvailable();
        Propagator p = propagator.getIfAvailable();
        if (t == null || p == null || t.currentTraceContext().context() == null) return null;
        Map<String, String> carrier = new HashMap<>();
        p.inject(t.currentTraceContext().context(), carrier, Map::put);
        return carrier.get(HEADER);
    }

    /** Runs `send` inside a span that continues the captured trace. */
    public void continueTrace(String traceparent, String name, Runnable send) {
        Tracer t = tracer.getIfAvailable();
        Propagator p = propagator.getIfAvailable();
        if (traceparent == null || t == null || p == null) {
            send.run();
            return;
        }
        Span span = p.extract(Map.of(HEADER, traceparent), Map::get).name(name).kind(Span.Kind.PRODUCER).start();
        try (Tracer.SpanInScope scope = t.withSpan(span)) {
            send.run();
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
