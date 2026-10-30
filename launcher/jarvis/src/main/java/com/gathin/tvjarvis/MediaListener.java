package com.gathin.tvjarvis;

import android.service.notification.NotificationListenerService;

/**
 * Exists only so Jarvis may see the TV's media sessions (pause, next, seek, "what is playing"). Android ties that
 * to notification access, granted from the shell; no notification is read.
 */
public class MediaListener extends NotificationListenerService {
}
