// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.terminal

import com.intellij.openapi.project.Project

/**
 * Bridge to the Reworked Terminal frontend content module: creates a Reworked engine tab
 * and runs a command in it. Implemented in the frontend module (see frontend.xml).
 */
interface ReworkedTabCommandRunner {
  fun createTabAndRunCommand(project: Project, workingDirectory: String?, tabName: String, command: String): Boolean
}
