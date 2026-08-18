package com.ionutbanu.scopetracer.plugin;

import com.intellij.codeInsight.navigation.NavigationUtil;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.ionutbanu.scopetracer.plugin.model.CallSite;
import java.awt.Component;

/**
 * Resolves a parsed {@link CallSite} to a project class via PSI and navigates to it — the exact
 * line when known, otherwise the class declaration. Zero matches shows a notification; multiple
 * matches (common in multi-module projects) shows IntelliJ's own disambiguation popup.
 */
final class TaskSourceNavigator {

  private static final Logger LOG = Logger.getInstance(TaskSourceNavigator.class);

  private TaskSourceNavigator() {}

  static void navigate(Project project, Component popupAnchor, CallSite callSite) {
    LOG.info("Scope Tracer: navigate() submitting PSI lookup for " + callSite);
    // The PSI short-names lookup below can scan the whole project index, which the platform
    // forbids running synchronously on the EDT (flagged as a "slow operation"). Run it on a
    // background read action and only touch UI (navigation, popups) back on the EDT.
    ReadAction.nonBlocking(
            () -> {
              var scope = GlobalSearchScope.projectScope(project);
              var classes =
                  PsiShortNamesCache.getInstance(project)
                      .getClassesByName(callSite.className(), scope);
              LOG.info(
                  "Scope Tracer: PSI lookup for '"
                      + callSite.className()
                      + "' found "
                      + classes.length
                      + " class(es)");
              return classes;
            })
        .finishOnUiThread(
            ModalityState.defaultModalityState(),
            classes -> handleResult(project, popupAnchor, callSite, classes))
        .submit(AppExecutorUtil.getAppExecutorService());
  }

  private static void handleResult(
      Project project, Component popupAnchor, CallSite callSite, PsiClass[] classes) {
    LOG.info("Scope Tracer: handleResult() on EDT with " + classes.length + " class(es)");
    if (classes.length == 0) {
      NotificationGroupManager.getInstance()
          .getNotificationGroup("Scope Tracer")
          .createNotification(
              "No class named '" + callSite.className() + "' found in this project",
              NotificationType.INFORMATION)
          .notify(project);
      return;
    }
    if (classes.length == 1) {
      navigateTo(project, classes[0], callSite.line());
      return;
    }
    NavigationUtil.getPsiElementPopup(classes, "Choose class for " + callSite.className())
        .showUnderneathOf(popupAnchor);
  }

  private static void navigateTo(Project project, PsiClass psiClass, Integer line) {
    if (line == null) {
      psiClass.navigate(true);
      return;
    }
    var virtualFile = psiClass.getContainingFile().getVirtualFile();
    if (virtualFile == null) {
      psiClass.navigate(true);
      return;
    }
    new OpenFileDescriptor(project, virtualFile, line - 1, 0).navigate(true);
  }
}
