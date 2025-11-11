package com.hoop.plugin.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

@Service(Service.Level.PROJECT)
@State(
    name = "HoopSettings",
    storages = [Storage("hoop-plugin.xml")]
)
class HoopSettings : PersistentStateComponent<HoopSettings> {
    
    var savedConnections: MutableMap<String, Int> = mutableMapOf()
    
    override fun getState(): HoopSettings {
        return this
    }
    
    override fun loadState(state: HoopSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }
    
    companion object {
        fun getInstance(project: Project): HoopSettings {
            return project.getService(HoopSettings::class.java)
        }
    }
}