// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.terminal.scripts

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.jetbrains.plugins.terminal.ReworkedTabCommandRunner
import org.jetbrains.plugins.terminal.TerminalEngine
import org.jetbrains.plugins.terminal.TerminalOptionsProvider
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.awt.BorderLayout
import java.awt.Component
import java.awt.CardLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.util.Locale
import javax.swing.ButtonGroup
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

internal class ScriptsToolWindowFactory : ToolWindowFactory, DumbAware {
  override fun shouldBeAvailable(project: Project): Boolean = true

  override fun init(toolWindow: ToolWindow) {
    toolWindow.stripeTitle = "Scripts"
  }

  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val panel = ScriptsPanel(project)
    val content = ContentFactory.getInstance().createContent(panel, null, true)
    toolWindow.contentManager.addContent(content)
  }
}

internal class ScriptConfig {
  var name: String = ""
  var isText: Boolean = false
  var script: String = ""
  var workDir: String = ""
}

@Service(Service.Level.PROJECT)
@State(name = "ScriptsToolWindowConfigurations", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
internal class ScriptsConfigService : PersistentStateComponent<ScriptsConfigService.State> {
  class State {
    var configs: MutableList<ScriptConfig> = mutableListOf()
  }

  private var state = State()

  override fun getState(): State = state

  override fun loadState(s: State) {
    state = s
  }

  fun configs(): MutableList<ScriptConfig> = state.configs

  companion object {
    fun getInstance(project: Project): ScriptsConfigService = project.getService(ScriptsConfigService::class.java)
  }
}

internal class ScriptsPanel(private val project: Project) : SimpleToolWindowPanel(true) {
  private val listModel = DefaultListModel<ScriptConfig>()
  private val list = JBList(listModel)

  init {
    toolbar = buildToolbar().component
    setContent(JBScrollPane(list))
    list.cellRenderer = ScriptRenderer()
    list.addMouseListener(object : MouseAdapter() {
      override fun mouseClicked(e: MouseEvent) {
        val index = list.locationToIndex(e.point)
        if (index < 0 || listModel.size() <= index) return
        val bounds = list.getCellBounds(index, index) ?: return
        if (!bounds.contains(e.point)) return
        if (SwingUtilities.isLeftMouseButton(e)) {
          runOrSwitch(listModel[index])
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
    reloadList()
  }

  private fun buildToolbar(): com.intellij.openapi.actionSystem.ActionToolbar {
    val group = DefaultActionGroup()
    group.add(object : AnAction("Add Script", "Add a new script configuration", AllIcons.General.Add) {
      override fun actionPerformed(e: AnActionEvent) {
        val dialog = ScriptConfigDialog(project, null)
        if (dialog.showAndGet()) {
          ScriptsConfigService.getInstance(project).configs().add(dialog.getCfg())
          reloadList()
        }
      }
    })
    return ActionManager.getInstance().createActionToolbar("ScriptsToolbar", group, true)
  }

  private fun reloadList() {
    listModel.clear()
    ScriptsConfigService.getInstance(project).configs().forEach { listModel.addElement(it) }
  }

  private fun tabNameOf(cfg: ScriptConfig) = "script:${cfg.name}"

  private fun isOpen(cfg: ScriptConfig): Boolean =
    TerminalToolWindowManager.getInstance(project).toolWindow?.contentManager?.contents
      ?.firstOrNull { it.displayName == "script:${cfg.name}" } != null

  private fun workDirOf(cfg: ScriptConfig): String? =
    cfg.workDir.trim().ifEmpty { project.basePath }

  private fun commandFor(cfg: ScriptConfig): String {
    if (cfg.isText) return cfg.script
    val ext = File(cfg.script).extension.lowercase(Locale.ROOT)
    val quoted = "\"${cfg.script}\""
    return when (ext) {
      "ps1" -> "powershell -NoProfile -ExecutionPolicy Bypass -File $quoted"
      "bat", "cmd" -> "cmd /c $quoted"
      "py" -> "python $quoted"
      "sh" -> "bash $quoted"
      else -> cfg.script
    }
  }

  // Follow the Terminal Engine setting: REWORKED goes through the frontend-module runner
  // (Reworked engine renderer), anything else uses the classic API. Falls back to classic
  // when the frontend service is unavailable.
  private fun executeInNewTab(tabName: String, workDir: String?, command: String) {
    ApplicationManager.getApplication().invokeLater {
      var started = false
      if (TerminalOptionsProvider.getInstance().terminalEngine == TerminalEngine.REWORKED) {
        started = runCatching { project.getService(ReworkedTabCommandRunner::class.java) }
          .getOrNull()?.createTabAndRunCommand(project, workDir, tabName, command) ?: false
      }
      if (!started) {
        runCatching {
          val widget = TerminalToolWindowManager.getInstance(project)
            .createLocalShellWidget(workDir, tabName) ?: return@invokeLater
          widget.executeCommand(command)
        }
      }
      list.repaint()
    }
  }

  private fun runOrSwitch(cfg: ScriptConfig) {
    val tabName = "script:${cfg.name}"
    val tw = TerminalToolWindowManager.getInstance(project).toolWindow
    val existing = tw?.contentManager?.contents?.firstOrNull { it.displayName == tabName }
    if (existing != null) {
      tw.activate {
        tw.contentManager.setSelectedContent(existing, true)
      }
      return
    }
    executeInNewTab(tabName, workDirOf(cfg), commandFor(cfg))
  }

  private fun rerunScript(cfg: ScriptConfig) {
    val tabName = "script:${cfg.name}"
    val tw = TerminalToolWindowManager.getInstance(project).toolWindow
    val existing = tw?.contentManager?.contents?.firstOrNull { it.displayName == tabName }
    if (existing != null) {
      runCatching { existing.manager?.removeContent(existing, true) }
    }
    executeInNewTab(tabName, workDirOf(cfg), commandFor(cfg))
  }

  private fun showContextMenu(e: MouseEvent, cfg: ScriptConfig) {
    val group = DefaultActionGroup()
    group.add(object : AnAction("Rerun", "Run this script in a new terminal tab", AllIcons.Actions.Rerun) {
      override fun actionPerformed(e: AnActionEvent) = rerunScript(cfg)
    })
    group.add(object : AnAction("Edit", "Edit this script configuration", AllIcons.Actions.Edit) {
      override fun actionPerformed(e: AnActionEvent) {
        val dialog = ScriptConfigDialog(project, cfg)
        if (dialog.showAndGet()) {
          val newCfg = dialog.getCfg()
          val configs = ScriptsConfigService.getInstance(project).configs()
          val idx = configs.indexOf(cfg)
          if (idx >= 0) configs[idx] = newCfg else configs.add(newCfg)
          reloadList()
        }
      }
    })
    group.addSeparator()
    group.add(object : AnAction("Delete", "Delete this script configuration", AllIcons.Actions.GC) {
      override fun actionPerformed(e: AnActionEvent) {
        val answer = Messages.showYesNoDialog(project,
          "Delete script configuration \"${cfg.name}\"?",
          "Delete Script", Messages.getQuestionIcon())
        if (answer != Messages.YES) return@actionPerformed
        val content = TerminalToolWindowManager.getInstance(project).toolWindow?.contentManager?.contents
          ?.firstOrNull { it.displayName == "script:${cfg.name}" }
        if (content != null) {
          runCatching { content.manager?.removeContent(content, true) }
        }
        ScriptsConfigService.getInstance(project).configs().remove(cfg)
        reloadList()
      }
    })
    val popup = JBPopupFactory.getInstance().createActionGroupPopup(
      null, group, DataManager.getInstance().getDataContext(list),
      JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false)
    popup.show(RelativePoint(e))
  }

  private val dotIcon = IconLoader.getIcon("/icons/rebased/sessionDot.svg", ScriptsPanel::class.java)
  private val cmdIcon = IconLoader.getIcon("/icons/rebased/script-cmd.svg", ScriptsPanel::class.java)
  private val ps1Icon = IconLoader.getIcon("/icons/rebased/script-ps1.svg", ScriptsPanel::class.java)
  private val batIcon = IconLoader.getIcon("/icons/rebased/script-bat.svg", ScriptsPanel::class.java)
  private val pyIcon = IconLoader.getIcon("/icons/rebased/script-py.svg", ScriptsPanel::class.java)
  private val shIcon = IconLoader.getIcon("/icons/rebased/script-sh.svg", ScriptsPanel::class.java)
  private val fileIcon = IconLoader.getIcon("/icons/rebased/script-file.svg", ScriptsPanel::class.java)

  private fun iconFor(cfg: ScriptConfig) = when {
    cfg.isText -> cmdIcon
    else -> when (File(cfg.script).extension.lowercase(Locale.ROOT)) {
      "ps1" -> ps1Icon
      "bat", "cmd" -> batIcon
      "py" -> pyIcon
      "sh" -> shIcon
      else -> fileIcon
    }
  }

  // Native two-line row: leading script-type icon, bold name (+ trailing open-dot), gray command preview.
  private inner class ScriptRenderer : JPanel(BorderLayout()), ListCellRenderer<ScriptConfig> {
    private val titleLine = SimpleColoredComponent()
    private val metaLine = SimpleColoredComponent()

    init {
      isOpaque = true
      val lines = JPanel(GridLayout(2, 1))
      lines.isOpaque = false
      lines.add(titleLine)
      lines.add(metaLine)
      titleLine.border = JBUI.Borders.emptyBottom(4)
      add(lines, BorderLayout.CENTER)
      border = JBUI.Borders.empty(10, 12, 10, 10)
    }

    override fun getListCellRendererComponent(list: JList<out ScriptConfig>, value: ScriptConfig?, index: Int, selected: Boolean, focused: Boolean): Component {
      val cfg = value ?: return this
      titleLine.clear()
      metaLine.clear()
      titleLine.icon = iconFor(cfg)
      titleLine.iconTextGap = 12
      titleLine.append(cfg.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
      if (isOpen(cfg)) {
        titleLine.append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES, dotIcon)
      }
      metaLine.border = JBUI.Borders.emptyLeft(28)
      metaLine.append(commandFor(cfg), SimpleTextAttributes.GRAYED_ATTRIBUTES)
      background = if (selected) UIUtil.getListSelectionBackground(true) else list.background
      foreground = list.foreground
      return this
    }
  }
}

internal class ScriptConfigDialog(private val project: Project, private val existing: ScriptConfig?) : DialogWrapper(project) {
  private val nameField = JBTextField()
  private val fileRadio = JBRadioButton("Script file", true)
  private val textRadio = JBRadioButton("Script text")
  private val fileField = TextFieldWithBrowseButton()
  private val textArea = JBTextArea(8, 46)
  private val workDirField = TextFieldWithBrowseButton()
  private val cards = JPanel(CardLayout())

  init {
    title = if (existing == null) "Add Script Configuration" else "Edit Script Configuration"
    ButtonGroup().apply { add(fileRadio); add(textRadio) }
    fileField.addBrowseFolderListener("Select Script File", "Select the script file to execute", project,
                                      FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor())
    workDirField.addBrowseFolderListener("Select Working Directory", "Directory where the command is executed", project,
                                         FileChooserDescriptorFactory.createSingleFolderDescriptor())
    fileRadio.addActionListener { syncTypeUi() }
    textRadio.addActionListener { syncTypeUi() }

    cards.add(fileField, "file")
    cards.add(JBScrollPane(textArea), "text")
    cards.border = JBUI.Borders.emptyTop(8)

    existing?.let {
      nameField.text = it.name
      fileRadio.isSelected = !it.isText
      textRadio.isSelected = it.isText
      if (it.isText) textArea.text = it.script else fileField.text = it.script
      workDirField.text = it.workDir
    }
    syncTypeUi()
    init()
  }

  private fun syncTypeUi() {
    (cards.layout as CardLayout).show(cards, if (fileRadio.isSelected) "file" else "text")
  }

  override fun createCenterPanel(): JComponent {
    val typePanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
      isOpaque = false
      add(fileRadio)
      add(textRadio)
    }
    return FormBuilder.createFormBuilder()
      .addLabeledComponent("Name:", nameField)
      .addLabeledComponent("Type:", typePanel)
      .addComponent(cards)
      .addLabeledComponent("Working directory:", workDirField)
      .panel
  }

  override fun doValidate(): ValidationInfo? {
    if (nameField.text.trim().isEmpty()) return ValidationInfo("Name is required", nameField)
    val service = ScriptsConfigService.getInstance(project)
    val duplicate = service.configs().any { it.name == nameField.text.trim() && it !== existing }
    if (duplicate) return ValidationInfo("A script with this name already exists", nameField)
    if (fileRadio.isSelected && fileField.text.trim().isEmpty()) return ValidationInfo("Script file is required", fileField)
    if (textRadio.isSelected && textArea.text.trim().isEmpty()) return ValidationInfo("Script text is required", textArea)
    return null
  }

  fun getCfg(): ScriptConfig = ScriptConfig().apply {
    name = nameField.text.trim()
    isText = textRadio.isSelected
    script = if (isText) textArea.text.trim() else fileField.text.trim()
    workDir = workDirField.text.trim()
  }
}
