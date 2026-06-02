package com.valui.user.watch;

public record WatchCacheData(
        String controllerId,
        String externalEventId,
        String bookmaker,
        String matchTitle,
        String matchUrl,
        Long   startEpoch
) {}
