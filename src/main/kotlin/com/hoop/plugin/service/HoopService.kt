package com.hoop.plugin.service

import com.hoop.plugin.model.HoopConnection
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import java.io.BufferedReader
import java.io.InputStreamReader
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Service(Service.Level.PROJECT)
class HoopService(private val project: Project) {

    private val LOG = Logger.getInstance(HoopService::class.java)
    private val hoopPath = "/opt/homebrew/bin/hoop"
    private val activeProcesses = mutableMapOf<String, OSProcessHandler>()
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    companion object {
        fun getInstance(project: Project): HoopService {
            return project.getService(HoopService::class.java)
        }
    }

    /**
     * Etapa 1 - Login no Hoop (abre browser)
     */
    fun login(onComplete: (Boolean, String) -> Unit) {
        try {
            val commandLine = GeneralCommandLine(hoopPath, "login")
            val processHandler = OSProcessHandler(commandLine)

            processHandler.addProcessListener(object : ProcessAdapter() {
                override fun processTerminated(event: ProcessEvent) {
                    val exitCode = event.exitCode
                    if (exitCode == 0) {
                        onComplete(true, "✓ Login realizado com sucesso!")
                    } else {
                        onComplete(false, "✗ Erro no login. Exit code: $exitCode")
                    }
                }
            })

            processHandler.startNotify()

        } catch (e: Exception) {
            LOG.error("Erro ao executar login", e)
            onComplete(false, "✗ Erro: ${e.message}")
        }
    }

    /**
     * Etapa 2 - Lista as conexões disponíveis
     */
    fun listConnections(): List<HoopConnection> {
        try {
            val commandLine = GeneralCommandLine(hoopPath, "admin", "get", "connections")
            val process = commandLine.createProcess()
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            val connections = mutableListOf<HoopConnection>()
            var isFirstLine = true

            reader.useLines { lines ->
                lines.forEach { line ->
                    if (isFirstLine) {
                        isFirstLine = false
                        return@forEach // Pula o cabeçalho
                    }

                    if (line.isNotBlank()) {
                        val parts = line.trim().split(Regex("\\s+"), limit = 7)
                        if (parts.size >= 6) {
                            connections.add(
                                HoopConnection(
                                    name = parts[0],
                                    command = parts[1],
                                    type = parts[2],
                                    agent = parts[3],
                                    status = parts[4],
                                    port = 0  // Porta vazia inicialmente
                                )
                            )
                        }
                    }
                }
            }

            process.waitFor()
            return connections

        } catch (e: Exception) {
            LOG.error("Erro ao listar conexões", e)
            return emptyList()
        }
    }

    /**
     * Etapa 2.5 - Conecta nos bancos selecionados
     */
    fun connectToDatabase(
        connection: HoopConnection,
        onOutput: (String) -> Unit,
        onDisconnect: ((String, Int) -> Unit)? = null
    ) {
        try {
            val commandLine = GeneralCommandLine(
                hoopPath,
                "connect",
                connection.name,
                "--port",
                connection.port.toString()
            )

            val processHandler = OSProcessHandler(commandLine)

            processHandler.addProcessListener(object : ProcessAdapter() {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    onOutput(event.text)
                }

                override fun processTerminated(event: ProcessEvent) {
                    val exitCode = event.exitCode
                    val timestamp = LocalDateTime.now().format(timeFormatter)

                    // Remove do mapa de processos ativos
                    activeProcesses.remove(connection.name)

                    // Notifica sobre a desconexão
                    val message = when {
                        exitCode == 0 -> {
                            "⚠ [$timestamp] Conexão ${connection.name}:${connection.port} encerrada normalmente\n"
                        }
                        exitCode == 130 || exitCode == 143 -> {
                            "⚠ [$timestamp] Conexão ${connection.name}:${connection.port} foi cancelada (SIGINT/SIGTERM)\n"
                        }
                        else -> {
                            "⚠ [$timestamp] Conexão ${connection.name}:${connection.port} encerrada inesperadamente! Exit code: $exitCode\n"
                        }
                    }

                    onOutput(message)
                    onDisconnect?.invoke(connection.name, exitCode)

                    LOG.warn("Processo hoop encerrado: ${connection.name}, exit code: $exitCode")
                }

                override fun processWillTerminate(event: ProcessEvent, willBeDestroyed: Boolean) {
                    if (!willBeDestroyed) {
                        val timestamp = LocalDateTime.now().format(timeFormatter)
                        onOutput("⚠ [$timestamp] Processo ${connection.name} está sendo encerrado...\n")
                    }
                }
            })

            processHandler.startNotify()
            activeProcesses[connection.name] = processHandler

            LOG.info("Conexão iniciada: ${connection.name} na porta ${connection.port}")

        } catch (e: Exception) {
            LOG.error("Erro ao conectar", e)
            onOutput("✗ Erro ao conectar: ${e.message}\n")
        }
    }

    /**
     * Etapa 3 - Verifica as conexões ativas
     */
    fun getActiveConnections(): List<String> {
        try {
            val commandLine = GeneralCommandLine("ps", "-ef")
            val process = commandLine.createProcess()
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            val activeConnections = mutableListOf<String>()

            reader.useLines { lines ->
                lines.forEach { line ->
                    if (line.contains("hoop connect") && !line.contains("grep")) {
                        // Extrai o nome da conexão da linha
                        val parts = line.split("hoop connect")
                        if (parts.size > 1) {
                            val connectionInfo = parts[1].trim().split("--")[0].trim()
                            activeConnections.add(connectionInfo)
                        }
                    }
                }
            }

            process.waitFor()
            return activeConnections

        } catch (e: Exception) {
            LOG.error("Erro ao verificar conexões ativas", e)
            return emptyList()
        }
    }

    /**
     * Etapa 4 - Mata todos os processos Hoop
     */
    fun killAllConnections(onComplete: (Boolean, String) -> Unit) {
        try {
            // Mata os processos gerenciados pelo plugin
            activeProcesses.values.forEach { it.destroyProcess() }
            activeProcesses.clear()

            // Mata todos os processos hoop no sistema
            val commandLine = GeneralCommandLine("pkill", "hoop")
            val process = commandLine.createProcess()
            val exitCode = process.waitFor()

            if (exitCode == 0 || exitCode == 1) { // 1 = nenhum processo encontrado
                onComplete(true, "✓ Todas as conexões foram encerradas")
            } else {
                onComplete(false, "✗ Erro ao encerrar conexões. Exit code: $exitCode")
            }

        } catch (e: Exception) {
            LOG.error("Erro ao matar processos", e)
            onComplete(false, "✗ Erro: ${e.message}")
        }
    }

    /**
     * Retorna o número de processos ativos gerenciados pelo plugin
     */
    fun getActiveProcessCount(): Int = activeProcesses.size
}