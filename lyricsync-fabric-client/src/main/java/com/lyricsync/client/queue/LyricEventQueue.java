package com.lyricsync.client.queue;

import com.lyricsync.client.model.LyricUpdateMessage;

import java.util.concurrent.ConcurrentLinkedQueue;

public final class LyricEventQueue {
    private static final ConcurrentLinkedQueue<LyricUpdateMessage> QUEUE = new ConcurrentLinkedQueue<>();

    private LyricEventQueue() {
    }

    public static void offer(LyricUpdateMessage message) {
        if (message != null) {
            QUEUE.offer(message);
        }
    }

    public static LyricUpdateMessage poll() {
        return QUEUE.poll();
    }
}
