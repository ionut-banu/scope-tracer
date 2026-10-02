package com.ionutbanu.scopetracer.plugin;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;

/** Shows balloon notifications in the "Scope Tracer" notification group. */
final class ScopeTracerNotifier {

  private ScopeTracerNotifier() {}

  static void notify(Project project, String title, String content, NotificationType type) {
    NotificationGroupManager.getInstance()
        .getNotificationGroup("Scope Tracer")
        .createNotification(title, content, type)
        .notify(project);
  }
}
