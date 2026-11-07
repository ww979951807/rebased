// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.terminal.claude

import com.google.gson.JsonParser
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JLabel
import javax.swing.SwingConstants
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

internal data class ClaudeSessionInfo(val id: String, val title: String, val lastModified: Long)

internal class ClaudeSessionsPanel(private val project: Project) : SimpleToolWindowPanel(true) {
  private val listModel = DefaultListModel<ClaudeSessionInfo>()
  private val list = JBList(listModel)
  private val openTabs = HashMap<String, Content>()

  init {
    toolbar = buildToolbar().component
    setContent(JBScrollPane(list))
    list.cellRenderer = SessionRenderer()
    list.addMouseListener(object : MouseAdapter() {
      override fun mouseClicked(e: MouseEvent) {
        if (SwingUtilities.isLeftMouseButton(e)) {
          val index = list.locationToIndex(e.point)
          if (index >= 0 && listModel.size() > index) {
            openSession(listModel[index])
          }
        }
      }
    })
    refresh()
  }

  private fun buildToolbar(): com.intellij.openapi.actionSystem.ActionToolbar {
    val group = DefaultActionGroup()
    group.add(object : AnAction("New Session", "Start a new Claude session", AllIcons.General.Add) {
      override fun actionPerformed(e: AnActionEvent) = openClaude("claude:new", "claude")
    })
    group.add(object : AnAction("Refresh", "Rescan Claude sessions for this project", AllIcons.Actions.Refresh) {
      override fun actionPerformed(e: AnActionEvent) = refresh()
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
    val basePath = project.basePath ?: return emptyList()
    val slug = basePath.replace(Regex("[^A-Za-z0-9]"), "-")
    val dir = File(System.getProperty("user.home"), ".claude/projects/$slug")
    if (!dir.isDirectory) return emptyList()
    val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") } ?: return emptyList()
    return files.asSequence()
      .filter { it.length() > 0 }
      .map { f -> ClaudeSessionInfo(f.name.removeSuffix(".jsonl"), extractTitle(f) ?: "(no title)", f.lastModified()) }
      .sortedByDescending { it.lastModified }
      .toList()
  }

  private fun extractTitle(file: File): String? {
    var firstUser: String? = null
    runCatching {
      file.useLines { lines ->
        for (line in lines.take(300)) {
          val obj = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: continue
          val type = obj.get("type")?.takeIf { it.isJsonPrimitive }?.asString
          if (type == "summary") {
            val s = obj.get("summary")?.takeIf { it.isJsonPrimitive }?.asString
            if (!s.isNullOrBlank()) return s.trim().replace(Regex("\\s+"), " ").take(60)
          }
          if (firstUser == null && type == "user") {
            val text = extractUserText(obj)
            if (!text.isNullOrBlank() && !text.startsWith("<")) {
              firstUser = text.trim().replace(Regex("\\s+"), " ").take(60)
            }
          }
        }
      }
    }
    return firstUser
  }

  private fun extractUserText(obj: com.google.gson.JsonObject): String? {
    val content = obj.getAsJsonObject("message")?.get("content") ?: return null
    return when {
      content.isJsonPrimitive -> content.asString
      content.isJsonArray -> content.asJsonArray
        .filter { it.isJsonObject && it.asJsonObject.get("type")?.asString == "text" }
        .firstNotNullOfOrNull { it.asJsonObject.get("text")?.takeIf { t -> t.isJsonPrimitive }?.asString }
      else -> null
    }
  }

  private fun openSession(info: ClaudeSessionInfo) = openClaude("claude:${info.id.take(8)}", "claude --resume ${info.id}")

  private fun openClaude(tabName: String, command: String) {
    val manager = TerminalToolWindowManager.getInstance(project)
    val toolWindow = manager.toolWindow
    val shortId = tabName.removePrefix("claude:")
    val existing = openTabs[shortId]?.takeIf { !it.isDisposed }
                    ?: toolWindow?.contentManager?.contents?.firstOrNull { it.displayName == tabName }
    if (existing != null) {
      openTabs[shortId] = existing
      toolWindow.activate {
        toolWindow.contentManager.setSelectedContent(existing, true)
      }
      return
    }
    ApplicationManager.getApplication().invokeLater {
      val widget: ShellTerminalWidget = runCatching {
        manager.createLocalShellWidget(project.basePath, tabName)
      }.getOrNull() ?: return@invokeLater
      val content = manager.toolWindow?.contentManager?.contents
        ?.firstOrNull { it.displayName == tabName || it.component.components.any { c -> c == widget.component.parent } }
      if (content != null) {
        openTabs[shortId] = content
      }
      runCatching { widget.executeCommand(command) }
    }
  }

  private inner class SessionRenderer : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(list: JList<*>, value: Any?, index: Int, selected: Boolean, focused: Boolean): Component {
      val component = super.getListCellRendererComponent(list, value, index, selected, focused)
      val info = value as? ClaudeSessionInfo
      if (info != null && component is JLabel) {
        val time = SimpleDateFormat("MM-dd HH:mm").format(Date(info.lastModified))
        component.text = "<html><b>${escapeHtml(info.title)}</b>&nbsp;&nbsp;" +
                         "<span style='color:gray;'>${time}&nbsp;&middot;&nbsp;${info.id.take(8)}</span></html>"
        component.horizontalTextPosition = SwingConstants.LEFT
      }
      return component
    }
  }

  private fun escapeHtml(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
