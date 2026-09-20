package com.tingjian.assistant;

import android.service.notification.NotificationListenerService;

/** Grants access to published media sessions. Notification content is never inspected or stored. */
public final class PlaybackAccessService extends NotificationListenerService {
    @Override public void onListenerConnected() {
        super.onListenerConnected();
        ScreenAssistantService.onPlaybackAccessChanged();
    }

    @Override public void onListenerDisconnected() {
        super.onListenerDisconnected();
        ScreenAssistantService.onPlaybackAccessChanged();
    }
}
