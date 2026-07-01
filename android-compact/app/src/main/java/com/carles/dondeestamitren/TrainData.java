package com.carles.dondeestamitren;

import java.util.ArrayList;
import java.util.List;

final class TrainData {
    String id;
    String tripId;
    String routeId;
    String label;
    String service;
    double latitude;
    double longitude;
    long timestamp;
    Integer delaySeconds;
    String nextStopId;
    Long nextArrival;

    String displayName() {
        if (label != null && !label.isBlank()) return label;
        if (routeId != null && !routeId.isBlank()) return routeId;
        if (tripId != null && !tripId.isBlank()) return tripId;
        return id;
    }
}

final class DelayData {
    Integer delaySeconds;
    String nextStopId;
    Long nextArrival;
}

final class AlertData {
    String id;
    String title;
    String description;
    String effect;
    final List<String> routeIds = new ArrayList<>();
}
