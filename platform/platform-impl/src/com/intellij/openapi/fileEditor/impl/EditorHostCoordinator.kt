// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.fileEditor.impl

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.impl.ContentImpl
import com.intellij.util.concurrency.annotations.RequiresEdt
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

@Service(Service.Level.PROJECT)
internal class EditorHostCoordinator(private val project: Project) : Disposable {
  private var adapter: EditorHostAdapter? = null
  private var toolWindow: ToolWindow? = null

  @RequiresEdt
  fun bindVisibility(toolWindow: ToolWindow) {
    this.toolWindow = toolWindow
    val connection = project.messageBus.connect(toolWindow.disposable)
    connection.subscribe(FileEditorManagerListener.Before.FILE_EDITOR_MANAGER, object : FileEditorManagerListener.Before {
      override fun beforeFileOpened(source: FileEditorManager, file: VirtualFile) {
        if (!toolWindow.isVisible) {
          toolWindow.show(null)
        }
      }
    })
    connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
      override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        if (!toolWindow.isVisible) {
          toolWindow.show(null)
        }
      }

      override fun selectionChanged(event: FileEditorManagerEvent) {
        if (event.manager.hasOpenFiles() && !toolWindow.isVisible) {
          toolWindow.show(null)
        }
      }

      override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
        if (!source.hasOpenFiles() && toolWindow.isVisible) {
          toolWindow.hide(null)
        }
      }
    })
    ToolWindowManager.getInstance(project).invokeLater {
      if (FileEditorManager.getInstance(project).hasOpenFiles() && !toolWindow.isVisible) {
        toolWindow.show(null)
      }
    }
  }

  // Entry-point guarantee: reopening an already-open, already-selected file produces no
  // fileOpened/selectionChanged events, so show the host explicitly from openFile calls.
  // No visibility gating here: the deferred run may execute before the opened file is
  // registered, so checking hasOpenFiles() would race and miss the first-ever open.
  fun ensureShown() {
    val tw = toolWindow ?: return
    ToolWindowManager.getInstance(project).invokeLater {
      if (!tw.isVisible) {
        tw.show(null)
      }
    }
  }

  @RequiresEdt
  fun install(toolWindow: ToolWindow, fileEditorManager: FileEditorManagerImpl): Content {
    val mainSplitters = fileEditorManager.mainSplitters
    val currentAdapter = adapter ?: EditorHostAdapter(mainSplitters).also { adapter = it }
    check(currentAdapter.editorComponent === mainSplitters) {
      "File Editor host cannot be changed after initialization"
    }

    val contentManager = toolWindow.contentManager
    contentManager.contents.firstOrNull { it.component === currentAdapter }?.let { return it }
    contentManager.contents.firstOrNull { it.component === mainSplitters }?.let {
      contentManager.removeContent(it, false)
    }

    val content = ContentFactory.getInstance().createContent(currentAdapter, null, false).apply {
      isCloseable = false
      (this as ContentImpl).setShouldDisposeContent(false)
    }
    contentManager.addContent(content)
    return content
  }

  @RequiresEdt
  override fun dispose() {
    toolWindow = null
    adapter?.let { currentAdapter ->
      currentAdapter.remove(currentAdapter.editorComponent)
    }
    adapter = null
  }

  companion object {
    fun getInstance(project: Project): EditorHostCoordinator = project.service()
  }
}

internal class EditorHostAdapter(val editorComponent: JComponent) : JPanel(BorderLayout()) {
  init {
    name = "EditorHostAdapter"
    add(editorComponent, BorderLayout.CENTER)
  }
}
