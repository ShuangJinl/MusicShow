package com.lyricsync.client.model;

public record LyricUpdateMessage(String text, int duration, boolean isTranslated) {
}
