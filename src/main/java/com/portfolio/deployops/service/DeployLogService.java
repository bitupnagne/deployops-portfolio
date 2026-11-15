package com.portfolio.deployops.service;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 배포 로그 SSE 스트리밍 서비스.
 * - 요청당 다중 구독 허용(상세 화면 2곳에서 봐도 서로 안 끊음)
 * - 실행 중 발생한 이벤트를 버퍼에 보관했다가 늦게/재접속한 구독자에게 재생(재연결 구간 로그 유실 방지)
 * - 버퍼는 배포 종료(complete) 시 정리
 */
@Service
public class DeployLogService {

    private static final long EMITTER_TIMEOUT_MS = 600_000L; // 10분 — 긴 빌드 대기 중 타임아웃 재연결 최소화
    private static final int BUFFER_MAX_EVENTS = 1000;

    private record Event(String name, String data) {}

    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final Map<Long, Deque<Event>> buffers = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long requestId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        emitters.computeIfAbsent(requestId, key -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> removeEmitter(requestId, emitter));
        emitter.onTimeout(() -> removeEmitter(requestId, emitter));
        emitter.onError(e -> removeEmitter(requestId, emitter));

        Deque<Event> buffer = buffers.get(requestId);
        if (buffer != null) {
            for (Event event : buffer.toArray(new Event[0])) {
                if (!trySend(requestId, emitter, event)) {
                    break;
                }
            }
        }
        return emitter;
    }

    public void sendLog(Long requestId, String message) {
        dispatch(requestId, new Event("log", message));
    }

    public void sendStatus(Long requestId, String status) {
        dispatch(requestId, new Event("status", status));
    }

    public void complete(Long requestId) {
        List<SseEmitter> list = emitters.remove(requestId);
        buffers.remove(requestId);
        if (list == null) {
            return;
        }
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name("done").data("completed"));
            } catch (IOException ignored) {
                // 이미 끊긴 구독자 — complete()만 시도
            }
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // no-op
            }
        }
    }

    public boolean hasSubscriber(Long requestId) {
        List<SseEmitter> list = emitters.get(requestId);
        return list != null && !list.isEmpty();
    }

    private void dispatch(Long requestId, Event event) {
        Deque<Event> buffer = buffers.computeIfAbsent(requestId, key -> new ConcurrentLinkedDeque<>());
        buffer.addLast(event);
        while (buffer.size() > BUFFER_MAX_EVENTS) {
            buffer.pollFirst();
        }

        List<SseEmitter> list = emitters.get(requestId);
        if (list == null) {
            return;
        }
        for (SseEmitter emitter : list) {
            trySend(requestId, emitter, event);
        }
    }

    private boolean trySend(Long requestId, SseEmitter emitter, Event event) {
        try {
            emitter.send(SseEmitter.event().name(event.name()).data(event.data()));
            return true;
        } catch (IOException | IllegalStateException e) {
            removeEmitter(requestId, emitter);
            return false;
        }
    }

    private void removeEmitter(Long requestId, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(requestId);
        if (list == null) {
            return;
        }
        list.remove(emitter);
        if (list.isEmpty()) {
            emitters.remove(requestId, list);
        }
    }
}
