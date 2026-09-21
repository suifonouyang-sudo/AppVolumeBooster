package com.rk.appvolume;

import android.service.notification.NotificationListenerService;

/**
 * 空的通知监听服务。
 * 存在意义：MediaSessionManager.getActiveSessions() 要求调用方是
 * 「已启用的通知监听器」，本服务仅用于获得该资格，本身不做任何事。
 * 用户需在 设置 → 通知使用权 中启用本应用。
 */
public class NotiListener extends NotificationListenerService {
}
