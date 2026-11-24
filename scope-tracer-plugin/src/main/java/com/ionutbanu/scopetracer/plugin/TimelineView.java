package com.ionutbanu.scopetracer.plugin;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.jcef.JBCefBrowser;
import com.ionutbanu.scopetracer.plugin.model.ReportNavigation;
import com.ionutbanu.scopetracer.plugin.model.ReportViews.Timeline;
import java.awt.CardLayout;
import java.nio.file.Path;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLifeSpanHandlerAdapter;
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
 */
final class TimelineView {

  private static final Logger LOG = Logger.getInstance(TimelineView.class);

  static final String NO_RECORDING = "Open a recording to see its timeline.";

  private static final String MESSAGE_CARD = "message";
  private static final String BROWSER_CARD = "browser";

  private final Disposable parent;
  private final CardLayout cards = new CardLayout();
  private final JPanel root = new JPanel(cards);
  private final JBLabel message = new JBLabel(NO_RECORDING, SwingConstants.CENTER);
  private JBCefBrowser browser;
  private volatile Path report;

  TimelineView(Disposable parent) {
    this.parent = parent;
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
    root.add(b.getComponent(), BROWSER_CARD);
    browser = b;
    return b;
  }
}
