// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.terminal.claude

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.jetbrains.plugins.terminal.ReworkedTabCommandRunner
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import org.jetbrains.plugins.terminal.TerminalEngine
import org.jetbrains.plugins.terminal.TerminalOptionsProvider
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.awt.BorderLayout
import java.awt.Component
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.DefaultListModel
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.SwingUtilities

internal class ClaudeSessionsToolWindowFactory : ToolWindowFactory, DumbAware {
  override fun shouldBeAvailable(project: Project): Boolean = true

  override fun init(toolWindow: ToolWindow) {
    toolWindow.stripeTitle = "Claude Sessions"
  }

  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val panel = ClaudeSessionsPanel(project)
    val content = ContentFactory.getInstance().createContent(panel, null, true)
    toolWindow.contentManager.addContent(content)
  }
}

internal data class ClaudeSessionInfo(val id: String, val title: String, val lastModified: Long, val sizeBytes: Long, val messageCount: Int)

internal class ClaudeSessionsPanel(private val project: Project) : SimpleToolWindowPanel(true) {
  private val listModel = DefaultListModel<ClaudeSessionInfo>()
  private val list = JBList(listModel)
  @Volatile private var newestFirst = true
  private val aiTitleRegex = Regex("\"aiTitle\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
  private val textRegex = Regex("\"type\"\\s*:\\s*\"text\"\\s*,\\s*\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
  private val typeRegex = Regex("\"type\"\\s*:\\s*\"(\\w+)\"")

  init {
    toolbar = buildToolbar().component
    setContent(JBScrollPane(list))
    list.cellRenderer = SessionRenderer()
    list.addMouseListener(object : MouseAdapter() {
      override fun mouseClicked(e: MouseEvent) {
        val index = list.locationToIndex(e.point)
        if (index < 0 || listModel.size() <= index) return
        val bounds = list.getCellBounds(index, index) ?: return
        if (!bounds.contains(e.point)) return
        if (SwingUtilities.isLeftMouseButton(e)) {
          val info = listModel[index]
          if (e.clickCount >= 2) {
            // Double click: open the session (switch to its tab or start it in a new one).
            openSession(info)
          }
          else if (e.clickCount == 1) {
            // Single click: only switch to the tab when it is already open, never start anything.
            switchToOpenTab(info)
          }
        }
        else if (SwingUtilities.isRightMouseButton(e)) {
          list.selectedIndex = index
          showContextMenu(e, listModel[index])
        }
      }
    })
    runCatching {
      TerminalToolWindowManager.getInstance(project).toolWindow?.addContentManagerListener(object : ContentManagerListener {
        override fun contentRemoved(event: ContentManagerEvent) = list.repaint()
        override fun contentAdded(event: ContentManagerEvent) = list.repaint()
      })
    }
    refresh()
  }

  private fun buildToolbar(): com.intellij.openapi.actionSystem.ActionToolbar {
    val group = DefaultActionGroup()
    group.add(object : AnAction("New Session", "Start a new Claude session", AllIcons.General.Add) {
      override fun actionPerformed(e: AnActionEvent) {
        openClaude("claude:new", "claude")
        watchForNewSessionFile()
      }
    })
    group.add(object : AnAction("Refresh", "Rescan Claude sessions for this project", AllIcons.Actions.Refresh) {
      override fun actionPerformed(e: AnActionEvent) = refresh()
    })
    group.add(object : AnAction(
      if (newestFirst) "Newest Last" else "Newest First",
      "Toggle sort order of the session list",
      AllIcons.Ide.UpDown
    ) {
      override fun actionPerformed(e: AnActionEvent) {
        newestFirst = !newestFirst
        refresh()
      }
    })
    return ActionManager.getInstance().createActionToolbar("ClaudeSessionsToolbar", group, true)
  }

  private fun refresh() {
    AppExecutorUtil.getAppExecutorService().execute {
      val sessions = readSessions()
      SwingUtilities.invokeLater {
        listModel.clear()
        sessions.forEach { listModel.addElement(it) }
      }
    }
  }

  private fun readSessions(): List<ClaudeSessionInfo> {
    val dir = projectDir()
    if (!dir.isDirectory) return emptyList()
    val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") } ?: return emptyList()
    val sessions = files.asSequence()
      .filter { it.length() > 0 }
      .map { f ->
        val id = f.name.removeSuffix(".jsonl")
        ClaudeSessionInfo(id, titleOverride(id) ?: extractTitle(f) ?: "(no title)", f.lastModified(), f.length(), countMessages(f))
      }
      .sortedByDescending { it.lastModified }
      .toList()
    return if (newestFirst) sessions else sessions.asReversed()
  }

  private fun countMessages(file: File): Int {
    var count = 0
    runCatching {
      file.useLines { lines ->
        for (line in lines) {
          val type = typeRegex.find(line)?.groupValues?.get(1)
          if (type == "user" || type == "assistant") count++
        }
      }
    }
    return count
  }

  private fun extractTitle(file: File): String? {
    var aiTitle: String? = null
    var firstUser: String? = null
    runCatching {
      file.useLines { lines ->
        for (line in lines) {
          // ai-title lines: {"type":"ai-title","aiTitle":"..."} — detect by key presence, the
          // typeRegex (\w+) cannot match the hyphenated "ai-title" value. Take the LAST one.
          if (line.contains("\"aiTitle\"")) {
            val s = aiTitleRegex.find(line)?.groupValues?.get(1)
            if (!s.isNullOrBlank()) {
              aiTitle = unescape(s).trim().replace(Regex("\\s+"), " ").take(60)
            }
          }
          if (firstUser == null) {
            val type = typeRegex.find(line)?.groupValues?.get(1)
            if (type == "user") {
              val raw = textRegex.find(line)?.groupValues?.get(1)
                ?: stringContent(line)
              if (raw != null) {
                val text = unescape(raw).trim().replace(Regex("\\s+"), " ")
                if (text.isNotEmpty() && !text.startsWith("<") && !text.startsWith("[")) {
                  firstUser = text.take(60)
                }
              }
            }
          }
        }
      }
    }
    return aiTitle ?: firstUser
  }

  private fun stringContent(line: String): String? {
    if (!line.contains("\"role\"\\s*:\\s*\"user\"".toRegex())) return null
    val m = Regex("\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(line) ?: return null
    return m.groupValues.getOrNull(1)
  }

  private fun unescape(s: String): String {
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
      val c = s[i]
      if (c == '\\' && i + 1 < s.length) {
        when (val n = s[i + 1]) {
          'n' -> { sb.append('\n'); i += 2 }
          't' -> { sb.append('\t'); i += 2 }
          'r' -> { sb.append('\r'); i += 2 }
          '"' -> { sb.append('"'); i += 2 }
          '\\' -> { sb.append('\\'); i += 2 }
          '/' -> { sb.append('/'); i += 2 }
          'u' -> {
            val code = if (i + 6 <= s.length) s.substring(i + 2, i + 6).toIntOrNull(16) else null
            if (code != null) { sb.append(code.toChar()); i += 6 } else { sb.append(n); i += 2 }
          }
          else -> { sb.append(n); i += 2 }
        }
      }
      else {
        sb.append(c)
        i++
      }
    }
    return sb.toString()
  }

  private fun projectDir(): File {
    val cfg = File(System.getProperty("user.home"), ".claude/projects")
    val slug = (project.basePath ?: "").replace(Regex("[^A-Za-z0-9]"), "-")
    return File(cfg, slug)
  }

  private fun sessionFile(id: String) = File(projectDir(), "$id.jsonl")

  private fun isSessionOpen(id: String): Boolean =
    openClaudeContent(id) != null

  private fun openClaudeContent(id: String): Content? {
    val shortId = id.take(8)
    val tw = TerminalToolWindowManager.getInstance(project).toolWindow ?: return null
    return tw.contentManager.contents.firstOrNull { it.displayName == "claude:$shortId" }
  }

  private fun openSession(info: ClaudeSessionInfo) = openClaude("claude:${info.id.take(8)}", "claude --resume ${info.id}")

  // Single click: switch to the session's terminal tab only when it is already open.
  private fun switchToOpenTab(info: ClaudeSessionInfo) {
    val content = openClaudeContent(info.id) ?: return
    val tw = TerminalToolWindowManager.getInstance(project).toolWindow ?: return
    tw.activate { tw.contentManager.setSelectedContent(content, true) }
  }

  private fun openClaude(tabName: String, command: String) {
    val toolWindow = TerminalToolWindowManager.getInstance(project).toolWindow
    val existing = toolWindow?.contentManager?.contents?.firstOrNull { it.displayName == tabName }
    if (existing != null) {
      toolWindow.activate {
        toolWindow.contentManager.setSelectedContent(existing, true)
      }
      return
    }
    startCommand(tabName, command)
  }

  private fun rerunSession(info: ClaudeSessionInfo) {
    val tabName = "claude:${info.id.take(8)}"
    val toolWindow = TerminalToolWindowManager.getInstance(project).toolWindow
    val existing = toolWindow?.contentManager?.contents?.firstOrNull { it.displayName == tabName }
    if (existing != null) {
      runCatching { existing.manager?.removeContent(existing, true) }
    }
    startCommand(tabName, "claude --resume ${info.id}")
  }

  // Follow the Terminal Engine setting: REWORKED goes through the frontend-module runner
  // (Reworked engine renderer), anything else uses the classic API. Falls back to classic
  // when the frontend service is unavailable.
  private fun startCommand(tabName: String, command: String) {
    val workDir = project.basePath
    ApplicationManager.getApplication().invokeLater {
      var started = false
      if (TerminalOptionsProvider.instance.terminalEngine == TerminalEngine.REWORKED) {
        started = runCatching { project.getService(ReworkedTabCommandRunner::class.java) }
          .getOrNull()?.createTabAndRunCommand(project, workDir, tabName, command) ?: false
      }
      if (!started) {
        runCatching {
          val widget: ShellTerminalWidget = TerminalToolWindowManager.getInstance(project)
            .createLocalShellWidget(workDir, tabName) ?: return@invokeLater
          widget.executeCommand(command)
        }
      }
      list.repaint()
    }
  }

  @Volatile private var newSessionWatch: ScheduledFuture<*>? = null

  // After starting a new session, poll until the session jsonl appears, refresh the list and
  // rename the "claude:new" tab to "claude:<shortId>" so the open-session dot lights up.
  private fun watchForNewSessionFile() {
    val clickTime = System.currentTimeMillis()
    val counter = AtomicInteger(0)
    newSessionWatch?.cancel(false)
    newSessionWatch = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({
      SwingUtilities.invokeLater {
        val n = counter.incrementAndGet()
        val tw = TerminalToolWindowManager.getInstance(project).toolWindow
        val content = tw?.contentManager?.contents?.firstOrNull { it.displayName == "claude:new" }
        val fresh = projectDir().listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }
          ?.filter { it.lastModified() > clickTime }
          ?.maxByOrNull { it.lastModified() }
        if (fresh != null && content != null) {
          content.setDisplayName("claude:${fresh.name.removeSuffix(".jsonl").take(8)}")
        }
        if (content == null || fresh != null || n >= 40) {
          newSessionWatch?.cancel(false)
          newSessionWatch = null
        }
        refresh()
      }
    }, 3, 3, TimeUnit.SECONDS)
  }

  private fun showContextMenu(e: MouseEvent, info: ClaudeSessionInfo) {
    val group = DefaultActionGroup()
    group.add(object : AnAction("Rerun", "Reopen this session in a new terminal tab", AllIcons.Actions.Rerun) {
      override fun actionPerformed(e: AnActionEvent) = rerunSession(info)
    })
    group.add(object : AnAction("Rename", "Set a custom title for this session", AllIcons.Actions.Edit) {
      override fun actionPerformed(e: AnActionEvent) {
        val input = Messages.showInputDialog(project, "Session title:", "Rename Session",
                                             Messages.getQuestionIcon(), info.title, null) ?: return@actionPerformed
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return@actionPerformed
        setTitleOverride(info.id, trimmed)
        refresh()
      }
    })
    group.add(object : AnAction("Reset Title", "Remove the custom title", AllIcons.Actions.Rollback) {
      override fun actionPerformed(e: AnActionEvent) {
        setTitleOverride(info.id, null)
        refresh()
      }
    })
    group.addSeparator()
    group.add(object : AnAction("Delete", "Delete this session", AllIcons.Actions.GC) {
      override fun actionPerformed(e: AnActionEvent) {
        val answer = Messages.showYesNoDialog(project,
          "Delete this Claude session?\n\n${info.title}\n${info.id.take(8)}",
          "Delete Session", Messages.getQuestionIcon())
        if (answer != Messages.YES) return@actionPerformed
        runCatching { sessionFile(info.id).delete() }
        val content = openClaudeContent(info.id)
        if (content != null) {
          runCatching { content.manager?.removeContent(content, true) }
        }
        refresh()
      }
    })
    val dataContext = object : DataContext {
      override fun getData(dataId: String): Any? = null
    }
    val popup = JBPopupFactory.getInstance().createActionGroupPopup(
      null, group, dataContext,
      JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false)
    popup.show(RelativePoint(e))
  }

  private fun titleOverride(id: String): String? {
    val f = File(projectDir(), "claude-session-titles.txt")
    if (!f.isFile) return null
    runCatching {
      f.useLines { lines ->
        for (line in lines) {
          val idx = line.indexOf('=')
          if (idx > 0 && line.substring(0, idx) == id) {
            val v = line.substring(idx + 1)
            if (v.isNotEmpty()) return v
          }
        }
      }
    }
    return null
  }

  private fun setTitleOverride(id: String, title: String?) {
    val f = File(projectDir(), "claude-session-titles.txt")
    val existing = LinkedHashMap<String, String>()
    runCatching {
      if (f.isFile) f.useLines { lines ->
        for (line in lines) {
          val idx = line.indexOf('=')
          if (idx > 0) existing[line.substring(0, idx)] = line.substring(idx + 1)
        }
      }
    }
    if (title == null) existing.remove(id) else existing[id] = title
    runCatching {
      f.parentFile.mkdirs()
      f.writeText(existing.entries.joinToString("\n") { "${it.key}=${it.value}" })
    }
  }

  private val sessionIcon = IconLoader.getIcon("/icons/rebased/claudeSessions.svg", ClaudeSessionsPanel::class.java)
  private val dotIcon = IconLoader.getIcon("/icons/rebased/sessionDot.svg", ClaudeSessionsPanel::class.java)

  // Native two-line row: leading session icon, bold title, gray meta line, green open-dot at
  // the row end. The panel stays opaque with an explicit background: WideSelectionListUI
  // spreads the renderer background across the FULL row width, which gives the prototype's
  // edge-to-edge selection. Selection is read from the model — the renderer callback may
  // pass selected=false even for the selected row.
  private inner class SessionRenderer : JPanel(BorderLayout()), ListCellRenderer<ClaudeSessionInfo> {
    private val titleLine = SimpleColoredComponent()
    private val metaLine = SimpleColoredComponent()
    private val dotLabel = JLabel(dotIcon)

    init {
      isOpaque = true
      val lines = JPanel(GridLayout(2, 1))
      lines.isOpaque = false
      lines.add(titleLine)
      lines.add(metaLine)
      titleLine.border = JBUI.Borders.emptyBottom(4)
      add(lines, BorderLayout.CENTER)
      dotLabel.border = JBUI.Borders.empty(0, 12, 0, 2)
      add(dotLabel, BorderLayout.EAST)
      border = JBUI.Borders.empty(10, 12, 10, 10)
    }

    override fun getListCellRendererComponent(list: JList<out ClaudeSessionInfo>, value: ClaudeSessionInfo?, index: Int, selected: Boolean, focused: Boolean): Component {
      val info = value ?: return this
      titleLine.clear()
      metaLine.clear()
      titleLine.icon = sessionIcon
      titleLine.iconTextGap = 12
      titleLine.append(info.title, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
      val time = SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date(info.lastModified))
      metaLine.border = JBUI.Borders.emptyLeft(28)
      metaLine.append("$time · ${formatSize(info.sizeBytes)} · ${info.id.take(8)} · ${info.messageCount}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
      val sel = selected || list.isSelectedIndex(index)
      background = if (sel) UIUtil.getListSelectionBackground(true) else list.background
      foreground = list.foreground
      dotLabel.isVisible = isSessionOpen(info.id)
      return this
    }
  }

  private fun formatSize(bytes: Long): String =
    when {
      bytes >= 1048576L -> String.format("%.1fMB", bytes / 1048576.0)
      bytes >= 1024L -> String.format("%.1fKB", bytes / 1024.0)
      else -> "${bytes}B"
    }
}
