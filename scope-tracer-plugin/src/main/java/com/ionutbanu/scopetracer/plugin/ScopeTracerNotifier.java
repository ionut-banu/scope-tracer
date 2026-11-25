package com.ionutbanu.scopetracer.plugin;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;

/** Shows balloon notifications in the "Scope Tracer" notification group. */
final class ScopeTracerNotifier {

  private ScopeTracerNotifier() {}

  static void notify(Project project, String title, String content, NotificationType type) {
    create(title, content, type).notify(project);
  }

  /** A notification with a "Configure…" action that opens Settings → Tools → Scope Tracer. */
  static void notifyWithConfigure(
      Project project, String title, String content, NotificationType type) {
    create(title, content, type)
        .addAction(
            NotificationAction.createSimpleExpiring(
                "Configure…", () -> AnalyzerJdkSettings.openSettings(project)))
        .notify(project);
  }

  private static Notification create(String title, String content, NotificationType type) {
    return NotificationGroupManager.getInstance()
        .getNotificationGroup("Scope Tracer")
        .createNotification(title, content, type);
  }
}
