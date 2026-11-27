package com.ionutbanu.scopetracer.plugin;

import com.intellij.ide.ui.LafManagerListener;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.ColorUtil;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefJSQuery;
import com.intellij.util.ui.UIUtil;
import com.ionutbanu.scopetracer.plugin.model.ReportNavigation;
import com.ionutbanu.scopetracer.plugin.model.ReportTheme;
import com.ionutbanu.scopetracer.plugin.model.ReportViews.Timeline;
import com.ionutbanu.scopetracer.plugin.model.TaskClick;
import java.awt.CardLayout;
import java.nio.file.Path;
import java.util.function.Consumer;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLifeSpanHandlerAdapter;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.network.CefRequest;

/**
 * The "Timeline" view (#62): the analyzer's own HTML report in a {@link JBCefBrowser}, loaded from
 * its temp file's {@code file:} URL, or a line of text when there is no report to show.
 *
 * <p>Only made after {@link JcefSupport#isSupported()} said yes. The browser is made on the first
 * report and disposed with {@code parent}, the tool window content's panel. It loads nothing but
 * the report's own file: any other navigation ({@link ReportNavigation}), a link opening a new tab
 * and a popup are refused, so the view is never replaced by something else. A fragment of the same
 * file (the recording overview's links) scrolls the page.
 *
 * <p>The report follows the IDE's UI theme (#191): once a page has loaded, and whenever the theme
 * changes, {@link ReportTheme} sets an explicit {@code data-theme} attribute on the page. The file
 * is never rewritten. A scripting failure is logged and changes nothing else.
 *
 * <p>A single click on a task's bar or row goes to its fork call site (#190). In the same {@code
 * onLoadEnd}, {@link TaskClick#script} installs a click handler on the page that sends the
 * element's {@code data-scope-id} and {@code data-task-id} back through a {@link JBCefJSQuery}; the
 * file on disk carries no bridge code. Only a well-formed {@link TaskClick} reaches {@code
 * onTaskClick}, on the EDT. The query belongs to the browser's client and is disposed with it. A
 * bridge failure is logged and loses the click only.
 */
final class TimelineView {

  private static final Logger LOG = Logger.getInstance(TimelineView.class);

  static final String NO_RECORDING = "Open a recording to see its timeline.";

  private static final String MESSAGE_CARD = "message";
  private static final String BROWSER_CARD = "browser";

  private final Disposable parent;
  private final Consumer<TaskClick> onTaskClick;
  private final CardLayout cards = new CardLayout();
  private final JPanel root = new JPanel(cards);
  private final JBLabel message = new JBLabel(NO_RECORDING, SwingConstants.CENTER);
  private JBCefBrowser browser;
  private JBCefJSQuery clickQuery; // null when it could not be made
  private volatile Path report;

  /** {@code onTaskClick} is called on the EDT with the task a click on the page named. */
  TimelineView(Disposable parent, Consumer<TaskClick> onTaskClick) {
    this.parent = parent;
    this.onTaskClick = onTaskClick;
    root.add(message, MESSAGE_CARD);
    cards.show(root, MESSAGE_CARD);
  }

  JComponent component() {
    return root;
  }

  /** On the EDT. */
  void show(Timeline timeline) {
    switch (timeline) {
      case Timeline.Page page -> showPage(page.file());
      case Timeline.Message text -> showMessage(text.text());
      case Timeline.None none -> showMessage(NO_RECORDING);
    }
  }

  private void showPage(Path file) {
    try {
      var b = browser();
      report = file;
      applyPageBackground(b);
      b.loadURL(file.toUri().toString());
      cards.show(root, BROWSER_CARD);
    } catch (RuntimeException e) {
      // JCEF said it was supported and then failed: keep "Tasks" working, say so here.
      LOG.warn("Scope Tracer: the embedded browser could not load the timeline", e);
      showMessage("The timeline could not be shown here; use Open in Browser.");
    }
  }

  private void showMessage(String text) {
    report = null;
    message.setText(text);
    cards.show(root, MESSAGE_CARD);
  }

  private JBCefBrowser browser() {
    if (browser != null) {
      return browser;
    }
    var b = JBCefBrowser.createBuilder().setEnableOpenDevToolsMenuItem(false).build();
    Disposer.register(parent, b);
    // Made before the first page loads, so the page's script has a query to call.
    clickQuery = createClickQuery(b);
    applyPageBackground(b);
    var client = b.getJBCefClient();
    client.addRequestHandler(
        new CefRequestHandlerAdapter() {
          @Override
          public boolean onBeforeBrowse(
              CefBrowser cefBrowser,
              CefFrame frame,
              CefRequest request,
              boolean userGesture,
              boolean isRedirect) {
            boolean allowed = ReportNavigation.allowed(request.getURL(), report);
            if (!allowed) {
              LOG.info("Scope Tracer: refused navigation to " + request.getURL());
            }
            return !allowed; // true cancels
          }

          @Override
          public boolean onOpenURLFromTab(
              CefBrowser cefBrowser, CefFrame frame, String targetUrl, boolean userGesture) {
            LOG.info("Scope Tracer: refused opening " + targetUrl + " in a new tab");
            return true;
          }
        },
        b.getCefBrowser());
    client.addLifeSpanHandler(
        new CefLifeSpanHandlerAdapter() {
          @Override
          public boolean onBeforePopup(
              CefBrowser cefBrowser, CefFrame frame, String targetUrl, String targetFrameName) {
            LOG.info("Scope Tracer: refused a popup to " + targetUrl);
            return true;
          }
        },
        b.getCefBrowser());
    client.addLoadHandler(
        new CefLoadHandlerAdapter() {
          @Override
          public void onLoadEnd(CefBrowser cefBrowser, CefFrame frame, int httpStatusCode) {
            if (frame != null && frame.isMain()) {
              applyTheme(cefBrowser);
              installTaskClick(cefBrowser);
            }
          }
        },
        b.getCefBrowser());
    ApplicationManager.getApplication()
        .getMessageBus()
        .connect(parent)
        .subscribe(
            LafManagerListener.TOPIC,
            (LafManagerListener) source -> {
              // Only a report on screen has a theme to change; a message or no browser yet does
              // nothing, and the next page gets the then-current theme in onLoadEnd.
              var current = browser;
              if (current != null && report != null) {
                applyPageBackground(current);
                applyTheme(current.getCefBrowser());
              }
            });
    root.add(b.getComponent(), BROWSER_CARD);
    browser = b;
    return b;
  }

  /**
   * The query a click on the page calls, or {@code null} (logged) when it cannot be made: the
   * report then loads and themes as before, without the click. {@link JBCefJSQuery#create}
   * registers it with the browser's client, which the browser disposes.
   */
  private JBCefJSQuery createClickQuery(JBCefBrowser b) {
    try {
      var query = JBCefJSQuery.create((JBCefBrowserBase) b);
      query.addHandler(
          message -> {
            try {
              onPageMessage(message);
            } catch (RuntimeException e) {
              LOG.warn("Scope Tracer: a click in the timeline could not be handled", e);
            }
            return null; // no reply to the page
          });
      return query;
    } catch (RuntimeException e) {
      LOG.warn("Scope Tracer: clicking a task in the timeline will not go to its source", e);
      return null;
    }
  }

  /** On JCEF's thread. Only a well-formed {@link TaskClick} goes on, to the EDT. */
  private void onPageMessage(String message) {
    var click = TaskClick.parse(message);
    if (click.isEmpty()) {
      LOG.info(
          "Scope Tracer: ignored a timeline message that is not a task click ("
              + (message == null ? "null" : message.length() + " chars")
              + ")");
      return;
    }
    ApplicationManager.getApplication()
        .invokeLater(
            () -> {
              try {
                onTaskClick.accept(click.get());
              } catch (RuntimeException e) {
                LOG.warn("Scope Tracer: a click in the timeline could not be handled", e);
              }
            });
  }

  /** Installs the page's click handler, on the report only, once per page. */
  private void installTaskClick(CefBrowser cefBrowser) {
    var query = clickQuery;
    if (query == null || !ReportNavigation.allowed(cefBrowser.getURL(), report)) {
      return;
    }
    try {
      cefBrowser.executeJavaScript(TaskClick.script(query.inject("msg")), cefBrowser.getURL(), 0);
    } catch (RuntimeException e) {
      LOG.warn("Scope Tracer: could not set up clicking a task in the timeline", e);
    }
  }

  /** Sets the page's own background to the IDE's, so nothing white shows before its styles. */
  private static void applyPageBackground(JBCefBrowser b) {
    try {
      b.setPageBackgroundColor(ColorUtil.toHtmlColor(UIUtil.getPanelBackground()));
    } catch (RuntimeException e) {
      LOG.warn("Scope Tracer: could not set the timeline's background", e);
    }
  }

  private static void applyTheme(CefBrowser cefBrowser) {
    try {
      cefBrowser.executeJavaScript(
          ReportTheme.script(!JBColor.isBright()), cefBrowser.getURL(), 0);
    } catch (RuntimeException e) {
      LOG.warn("Scope Tracer: could not set the timeline's theme", e);
    }
  }
}
