package com.mcreatik.gallery.config;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-IP token bucket for the guest API and login. Buckets are generous on purpose: hundreds of wedding
 * guests often share one venue Wi-Fi IP. Only abusive scraping/brute force should ever hit the limit.
 * In-memory (single instance); nothing about guests is persisted.
 */
@Component
public class PublicRateLimitFilter extends OncePerRequestFilter {

    private static final int LOGIN_CAPACITY = 10;
    private static final double LOGIN_REFILL_PER_SECOND = 0.1;

    private final Map<String, Bucket> publicBuckets = new ConcurrentHashMap<>();
    private final Map<String, Bucket> loginBuckets = new ConcurrentHashMap<>();
    private final int capacity;
    private final double refillPerSecond;

    public PublicRateLimitFilter(GalleryProperties properties) {
        this.capacity = properties.rateLimit().publicCapacity();
        this.refillPerSecond = properties.rateLimit().publicRefillPerSecond();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.startsWith("/api/public/") || path.equals("/api/auth/login"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String ip = request.getRemoteAddr();
        boolean login = request.getRequestURI().equals("/api/auth/login");
        Bucket bucket = login
                ? loginBuckets.computeIfAbsent(ip, k -> new Bucket(LOGIN_CAPACITY, LOGIN_REFILL_PER_SECOND))
                : publicBuckets.computeIfAbsent(ip, k -> new Bucket(capacity, refillPerSecond));
        if (!bucket.tryConsume()) {
            response.setStatus(429);
            response.setHeader("Retry-After", "5");
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"RATE_LIMITED\",\"message\":\"Too many requests\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    @Scheduled(fixedDelay = 300_000)
    public void evictIdle() {
        long cutoff = System.nanoTime() - 600_000_000_000L;
        publicBuckets.values().removeIf(b -> b.lastRefill < cutoff);
        loginBuckets.values().removeIf(b -> b.lastRefill < cutoff);
    }

    static final class Bucket {
        private final int capacity;
        private final double refillPerNano;
        private double tokens;
        private long lastRefill;

        Bucket(int capacity, double refillPerSecond) {
            this.capacity = capacity;
            this.refillPerNano = refillPerSecond / 1_000_000_000d;
            this.tokens = capacity;
            this.lastRefill = System.nanoTime();
        }

        synchronized boolean tryConsume() {
            long now = System.nanoTime();
            tokens = Math.min(capacity, tokens + (now - lastRefill) * refillPerNano);
            lastRefill = now;
            if (tokens >= 1) {
                tokens -= 1;
                return true;
            }
            return false;
        }
    }
}
