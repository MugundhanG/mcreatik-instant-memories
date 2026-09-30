package com.mcreatik.gallery.realtime;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.mcreatik.gallery.common.ApiException;
import com.mcreatik.gallery.config.GalleryProperties;
import com.mcreatik.gallery.event.EventStatus;
import com.mcreatik.gallery.photo.PhotoDtos;

@Component
public class SseGalleryBroadcaster implements GalleryBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(SseGalleryBroadcaster.class);
    private static final int MAX_SUBSCRIBERS_PER_EVENT = 5_000;
    /** Tells browsers how long to wait before reconnecting after a dropped stream. */
    private static final long CLIENT_RETRY_MS = 3_000;

    private final Map<UUID, Set<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    /** Sends happen off the caller's thread so a slow phone never delays photo processing. */
    private final ExecutorService sender = Executors.newVirtualThreadPerTaskExecutor();
    private final long emitterTimeoutMs;

    public SseGalleryBroadcaster(GalleryProperties properties) {
        this.emitterTimeoutMs = properties.realtime().emitterTimeout().toMillis();
    }

    @Override
    public SseEmitter subscribe(UUID eventId) {
        Set<SseEmitter> set = subscribers.computeIfAbsent(eventId, k -> ConcurrentHashMap.newKeySet());
        if (set.size() >= MAX_SUBSCRIBERS_PER_EVENT) {
            throw new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "TOO_MANY_VIEWERS",
                    "Too many live viewers, please retry shortly");
        }
        SseEmitter emitter = new SseEmitter(emitterTimeoutMs);
        set.add(emitter);
        Runnable remove = () -> set.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(() -> {
            remove.run();
            emitter.complete();
        });
        emitter.onError(e -> remove.run());
        try {
            emitter.send(SseEmitter.event().name("CONNECTED").reconnectTime(CLIENT_RETRY_MS).data("{}"));
        } catch (IOException e) {
            remove.run();
        }
        return emitter;
    }

    @Override
    public void photoReady(UUID eventId, PhotoDtos.PublicPhoto photo) {
        broadcast(eventId, SseEmitter.event().name("PHOTO_READY").id(photo.cursor())
                .data(new PhotoReadyPayload(eventId, photo), MediaType.APPLICATION_JSON));
    }

    @Override
    public void photoRemoved(UUID eventId, UUID photoId) {
        broadcast(eventId, SseEmitter.event().name("PHOTO_REMOVED")
                .data(new PhotoRemovedPayload(eventId, photoId), MediaType.APPLICATION_JSON));
    }

    @Override
    public void eventStatusChanged(UUID eventId, EventStatus status) {
        broadcast(eventId, SseEmitter.event().name("EVENT_STATUS")
                .data(new EventStatusPayload(eventId, status, status == EventStatus.LIVE), MediaType.APPLICATION_JSON));
    }

    @Override
    public int subscriberCount(UUID eventId) {
        Set<SseEmitter> set = subscribers.get(eventId);
        return set == null ? 0 : set.size();
    }

    /** Keeps idle connections alive through proxies/load balancers that close silent streams. */
    @Scheduled(fixedDelayString = "${gallery.realtime.heartbeat-interval:20s}")
    public void heartbeat() {
        subscribers.forEach((eventId, set) -> {
            if (set.isEmpty()) {
                subscribers.remove(eventId, set);
                return;
            }
            broadcast(eventId, SseEmitter.event().comment("ping"));
        });
    }

    private void broadcast(UUID eventId, SseEmitter.SseEventBuilder event) {
        Set<SseEmitter> set = subscribers.get(eventId);
        if (set == null || set.isEmpty()) {
            return;
        }
        var data = event.build();
        for (SseEmitter emitter : set) {
            sender.execute(() -> {
                try {
                    emitter.send(data);
                } catch (IOException | IllegalStateException e) {
                    set.remove(emitter);
                    emitter.completeWithError(e);
                }
            });
        }
    }

    @PreDestroy
    void shutdown() {
        subscribers.values().forEach(set -> set.forEach(SseEmitter::complete));
        sender.shutdown();
        log.info("Closed live gallery streams");
    }

    public record PhotoReadyPayload(UUID eventId, UUID photoId, String thumbnailUrl, String webUrl, Integer width,
                                    Integer height, java.time.Instant readyAt, String cursor) {
        PhotoReadyPayload(UUID eventId, PhotoDtos.PublicPhoto p) {
            this(eventId, p.id(), p.thumbnailUrl(), p.webUrl(), p.width(), p.height(), p.readyAt(), p.cursor());
        }
    }

    public record PhotoRemovedPayload(UUID eventId, UUID photoId) {
    }

    public record EventStatusPayload(UUID eventId, EventStatus status, boolean live) {
    }
}
