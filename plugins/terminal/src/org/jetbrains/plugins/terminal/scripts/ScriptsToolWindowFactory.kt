// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.terminal.scripts

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
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
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
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
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.util.Locale
import javax.swing.ButtonGroup
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
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

  private fun workDirOf(cfg: ScriptConfig): String =
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

  private fun executeInNewTab(tabName: String, workDir: String?, command: String) {
    ApplicationManager.getApplication().invokeLater {
      runCatching {
        val widget = TerminalToolWindowManager.getInstance(project)
          .createLocalShellWidget(workDir, tabName) ?: return@invokeLater
        widget.executeCommand(command)
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
    val menu = JPopupMenu()
    menu.add(JMenuItem("Rerun").apply {
      addActionListener { rerunScript(cfg) }
    })
    menu.add(JMenuItem("Edit").apply {
      addActionListener {
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
    menu.addSeparator()
    menu.add(JMenuItem("Delete").apply {
      addActionListener {
        val answer = Messages.showYesNoDialog(project,
          "Delete script configuration \"${cfg.name}\"?",
          "Delete Script", Messages.getQuestionIcon())
        if (answer != Messages.YES) return@addActionListener
        ScriptsConfigService.getInstance(project).configs().remove(cfg)
        reloadList()
      }
    })
    menu.show(list, e.x, e.y)
  }

  private inner class ScriptRenderer : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(list: JList<*>, value: Any?, index: Int, selected: Boolean, focused: Boolean): Component {
      val component = super.getListCellRendererComponent(list, value, index, selected, focused)
      val cfg = value as? ScriptConfig
      if (cfg != null && component is JLabel) {
        val dot = if (isOpen(cfg)) "<span style='color:#57965C;'>&#9679;</span>&nbsp;" else ""
        val preview = (if (cfg.isText) cfg.script.replace("\n", " ") else cfg.script).take(80)
        component.text = "<html><div style='padding:6px 8px;'>$dot<b>${escapeHtml(cfg.name)}</b><br>" +
                         "<span style='color:gray;font-size:90%;'>${escapeHtml(preview)}</span></div></html>"
      }
      return component
    }
  }

  private fun escapeHtml(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

internal class ScriptConfigDialog(project: Project, private val existing: ScriptConfig?) : DialogWrapper(project) {
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
    cards.add(JScrollPane(textArea), "text")

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
    val panel = JPanel(GridBagLayout())
    val c = GridBagConstraints()
    c.insets = Insets(6, 8, 6, 8)
    c.fill = GridBagConstraints.HORIZONTAL

    c.gridx = 0; c.gridy = 0; c.weightx = 0.0
    panel.add(JLabel("Name:"), c)
    c.gridx = 1; c.gridy = 0; c.weightx = 1.0
    panel.add(nameField, c)

    c.gridx = 0; c.gridy = 1; c.weightx = 0.0; c.fill = GridBagConstraints.NONE
    panel.add(JLabel("Type:"), c)
    c.gridx = 1; c.gridy = 1
    panel.add(JPanel(FlowLayout(FlowLayout.LEFT)).apply { add(fileRadio); add(textRadio) }, c)
    c.fill = GridBagConstraints.HORIZONTAL

    c.gridx = 0; c.gridy = 2; c.gridwidth = 2; c.weightx = 1.0; c.weighty = 1.0
    c.fill = GridBagConstraints.BOTH
    panel.add(cards, c)
    c.gridwidth = 1; c.weighty = 0.0; c.fill = GridBagConstraints.HORIZONTAL

    c.gridx = 0; c.gridy = 3; c.weightx = 0.0
    panel.add(JLabel("Working directory:"), c)
    c.gridx = 1; c.gridy = 3; c.weightx = 1.0
    panel.add(workDirField, c)

    panel.preferredSize = Dimension(560, 320)
    return panel
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
