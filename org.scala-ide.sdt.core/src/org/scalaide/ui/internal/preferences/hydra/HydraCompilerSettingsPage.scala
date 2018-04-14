package org.scalaide.ui.internal.preferences.hydra

import org.eclipse.jface.preference.FieldEditorPreferencePage
import org.eclipse.ui.IWorkbenchPreferencePage
import org.scalaide.core.IScalaPlugin
import org.eclipse.jface.preference.ComboFieldEditor
import org.eclipse.ui.IWorkbench
import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer
import org.eclipse.swt.widgets.Composite
import org.eclipse.swt.widgets.Control
import org.eclipse.jface.preference.StringFieldEditor
import org.scalaide.core.IScalaProject
import org.scalaide.core.internal.compiler.hydra.SourcePartitioner
import org.eclipse.jface.preference.IntegerFieldEditor

class HydraCompilerSettingsPage extends FieldEditorPreferencePage with IWorkbenchPreferencePage {

  setPreferenceStore(IScalaPlugin().getPreferenceStore)
  setDescription("Hydra Compiler command line arguments:")

  override def createFieldEditors(): Unit = {
    val sourcePartitionerComboValues = SourcePartitioner.values.map(v => Array(v.value, v.value)).toArray
    addField(new ComboFieldEditor(HydraCompilerSettingsPage.SourcePartitioner, "Source Partitioner", sourcePartitionerComboValues, getFieldEditorParent))
    addField(new IntegerFieldEditor(HydraCompilerSettingsPage.Cpus, "Cpus", getFieldEditorParent))
    addField(new StringFieldEditor(HydraCompilerSettingsPage.HydraStore, "Hydra Store", getFieldEditorParent))
    addField(new StringFieldEditor(HydraCompilerSettingsPage.PartitionFile, "Partition File", getFieldEditorParent))
  }
  
  override def createContents(parent: Composite): Control = {
    val control = super.createContents(parent)

    control
  }
  
  def init(workbench: IWorkbench): Unit = {}
}

class HydraCompilerSettingsPageInitializer extends AbstractPreferenceInitializer {
  override def initializeDefaultPreferences(): Unit = {
    val store = IScalaPlugin().getPreferenceStore
    
    store.setDefault(HydraCompilerSettingsPage.SourcePartitioner, SourcePartitioner.Auto.value)
    store.setDefault(HydraCompilerSettingsPage.Cpus, Math.max(1, Runtime.getRuntime().availableProcessors() / 2))
    store.setDefault(HydraCompilerSettingsPage.HydraStore, "")
    store.setDefault(HydraCompilerSettingsPage.PartitionFile, "")
  }
}

object HydraCompilerSettingsPage {
  final val Cpus = "CpuTextField"
  final val SourcePartitioner = "SourcePartitionerCombo"
  final val HydraStore = "HydraStorePath"
  final val PartitionFile = "PartitionFile"
}