package xyz.sleipnir.codeminimap.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.ServiceManager
import xyz.sleipnir.codeminimap.config.ConfigService

class ShowHideAction : AnAction() {
    private val configService = ServiceManager.getService(ConfigService::class.java)

    override fun actionPerformed(anActionEvent: AnActionEvent) {
        configService.state!!.disabled = !configService.state!!.disabled
        configService.notifyChange()
    }
}