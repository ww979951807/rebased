// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.terminal.frontend.toolwindow.impl

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import org.jetbrains.plugins.terminal.ReworkedTabCommandRunner

internal class ReworkedTabCommandRunnerImpl(private val project: Project) : ReworkedTabCommandRunner {
  override fun createTabAndRunCommand(project: Project, workingDirectory: String?, tabName: String, command: String): Boolean {
    return try {
      val tab = TerminalToolWindowTabsManager.getInstance(project).createTabBuilder()
        .workingDirectory(workingDirectory)
        .tabName(tabName)
        .createTab()
      tab.view.createSendTextBuilder()
        .shouldExecute()
        .send(command)
      true
    }
    catch (t: Throwable) {
      Logger.getInstance(ReworkedTabCommandRunnerImpl::class.java)
        .warn("Failed to create Reworked terminal tab '$tabName'", t)
      false
    }
  }
}
